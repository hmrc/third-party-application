/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import javax.inject.Inject
import play.api.Logger
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiSubscriptionFieldsConnector, EmailConnector, ThirdPartyDelegatedAuthorityConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.{DeleteApplicationRequest, RejectUpliftRequest}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.models.State.{State, _}
import uk.gov.hmrc.thirdpartyapplication.models.StateHistory.dateTimeOrdering
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future._
import scala.util.Failure

class GatekeeperService @Inject()(applicationRepository: ApplicationRepository,
                                  stateHistoryRepository: StateHistoryRepository,
                                  subscriptionRepository: SubscriptionRepository,
                                  auditService: AuditService,
                                  emailConnector: EmailConnector,
                                  apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                                  apiStore: ApiStore,
                                  applicationResponseCreator: ApplicationResponseCreator,
                                  trustedApplications: TrustedApplications,
                                  thirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector) {

  def fetchNonTestingAppsWithSubmittedDate(): Future[Seq[ApplicationWithUpliftRequest]] = {
    def appError(id: UUID) = new InconsistentDataState(s"App not found for id: $id")

    def historyError(id: UUID) = new InconsistentDataState(s"History not found for id: $id")

    def latestUpliftRequestState(histories: Seq[StateHistory]) = {
      for ((id, history) <- histories.groupBy(_.applicationId))
        yield id -> history.maxBy(_.changedAt)
    }

    val appsFuture = applicationRepository.fetchStandardNonTestingApps()
    val stateHistoryFuture = stateHistoryRepository.fetchByState(PENDING_GATEKEEPER_APPROVAL)
    for {
      apps <- appsFuture
      appIds = apps.map(_.id)
      histories <- stateHistoryFuture.map(_.filter(h => appIds.contains(h.applicationId)))
      appsMap = apps.groupBy(_.id).mapValues(_.head)
      historyMap = latestUpliftRequestState(histories)
    } yield DataUtil.zipper(appsMap, historyMap, ApplicationWithUpliftRequest.create, appError, historyError)
  }

  def fetchAppWithHistory(id: UUID): Future[ApplicationWithHistory] = {
    for {
      app <- fetchApp(id)
      history <- stateHistoryRepository.fetchByApplicationId(id)
    } yield {
      ApplicationWithHistory(ApplicationResponse(data = app,
        clientId = None,
        trusted = trustedApplications.isTrusted(app)),
        history.map(StateHistoryResponse.from))
    }
  }

  def approveUplift(applicationId: UUID, gatekeeperUserId: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def approve(existing: ApplicationData) = existing.copy(state = existing.state.toPendingRequesterVerification)

    def sendEmails(app: ApplicationData) = {
      val requesterEmail = app.state.requestedByEmailAddress.getOrElse(throw new RuntimeException("no requestedBy email found"))
      val verificationCode = app.state.verificationCode.getOrElse(throw new RuntimeException("no verification code found"))
      val recipients = app.admins.map(_.emailAddress) - requesterEmail

      if (recipients.nonEmpty) emailConnector.sendApplicationApprovedNotification(app.name, recipients)

      emailConnector.sendApplicationApprovedAdminConfirmation(app.name, verificationCode, Set(requesterEmail))
    }

    for {
      app <- fetchApp(applicationId)
      newApp <- applicationRepository.save(approve(app))
      _ <- insertStateHistory(app, PENDING_REQUESTER_VERIFICATION, Some(PENDING_GATEKEEPER_APPROVAL),
        gatekeeperUserId, GATEKEEPER, applicationRepository.save)
      _ = Logger.info(s"UPLIFT04: Approved uplift application:${app.name} appId:${app.id} appState:${app.state.name}" +
        s" appRequestedByEmailAddress:${app.state.requestedByEmailAddress} gatekeeperUserId:$gatekeeperUserId")
      _ = auditGatekeeperAction(gatekeeperUserId, app, ApplicationUpliftApproved)
      _ = recoverAll(sendEmails(newApp))
    } yield UpliftApproved

  }

  def rejectUplift(applicationId: UUID, request: RejectUpliftRequest)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def reject(existing: ApplicationData) = {
      existing.state.requireState(State.PENDING_GATEKEEPER_APPROVAL, State.TESTING)
      existing.copy(state = existing.state.toTesting)
    }

    def sendEmails(app: ApplicationData, reason: String) =
      emailConnector.sendApplicationRejectedNotification(app.name, app.admins.map(_.emailAddress), reason)

    for {
      app <- fetchApp(applicationId)
      newApp <- applicationRepository.save(reject(app))
      _ <- insertStateHistory(app, TESTING, Some(PENDING_GATEKEEPER_APPROVAL),
        request.gatekeeperUserId, GATEKEEPER, applicationRepository.save, Some(request.reason))
      _ = Logger.info(s"UPLIFT03: Rejected uplift application:${app.name} appId:${app.id} appState:${app.state.name}" +
        s" appRequestedByEmailAddress:${app.state.requestedByEmailAddress} reason:${request.reason}" +
        s" gatekeeperUserId:${request.gatekeeperUserId}")
      _ = auditGatekeeperAction(request.gatekeeperUserId, app, ApplicationUpliftRejected, Map("reason" -> request.reason))
      _ = recoverAll(sendEmails(newApp, request.reason))
    } yield UpliftRejected
  }

  def resendVerification(applicationId: UUID, gatekeeperUserId: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def rejectIfNotPendingVerification(existing: ApplicationData) = {
      existing.state.requireState(State.PENDING_REQUESTER_VERIFICATION, State.PENDING_REQUESTER_VERIFICATION)
      existing
    }

    def sendEmails(app: ApplicationData) = {
      val requesterEmail = app.state.requestedByEmailAddress.getOrElse(throw new RuntimeException("no requestedBy email found"))
      val verificationCode = app.state.verificationCode.getOrElse(throw new RuntimeException("no verification code found"))
      emailConnector.sendApplicationApprovedAdminConfirmation(app.name, verificationCode, Set(requesterEmail))
    }

    for {
      app <- fetchApp(applicationId)
      _ = rejectIfNotPendingVerification(app)
      _ = auditGatekeeperAction(gatekeeperUserId, app, ApplicationVerficationResent)
      _ = recoverAll(sendEmails(app))
    } yield UpliftApproved

  }

  def deleteApplication(applicationId: UUID, request: DeleteApplicationRequest)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    Logger.info(s"Deleting application $applicationId")

    def deleteSubscriptions(app: ApplicationData): Future[HasSucceeded] = {
      def deleteSubscription(subscription: APIIdentifier) = {
        for {
          _ <- apiStore.removeSubscription(app.wso2Username, app.wso2Password, app.wso2ApplicationName, subscription)
          _ <- subscriptionRepository.remove(app.id, subscription)
        } yield HasSucceeded
      }

      for {
        subscriptions <- apiStore.getSubscriptions(app.wso2Username, app.wso2Password, app.wso2ApplicationName)
        _ <- traverse(subscriptions)(deleteSubscription)
        _ <- apiSubscriptionFieldsConnector.deleteSubscriptions(app.tokens.production.clientId)
      } yield HasSucceeded
    }

    def sendEmails(app: ApplicationData) = {
      val requesterEmail = request.requestedByEmailAddress
      val recipients = app.admins.map(_.emailAddress)
      emailConnector.sendApplicationDeletedNotification(app.name, requesterEmail, recipients)
    }

    (for {
      app <- fetchApp(applicationId)
      _ <- deleteSubscriptions(app)
      _ <- thirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(app.tokens.production.clientId)
      _ <- apiStore.deleteApplication(app.wso2Username, app.wso2Password, app.wso2ApplicationName)
      _ <- applicationRepository.delete(applicationId)
      _ <- stateHistoryRepository.deleteByApplicationId(applicationId)
      _ = auditGatekeeperAction(request.gatekeeperUserId, app, ApplicationDeleted, Map("requestedByEmailAddress" -> request.requestedByEmailAddress))
      _ = recoverAll(sendEmails(app))
    } yield Deleted).recover {
      case _: NotFoundException => Deleted
    }
  }

  def blockApplication(applicationId: UUID)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def block(application: ApplicationData): ApplicationData = {
      application.copy(blocked = true)
    }

    for {
      app <- fetchApp(applicationId)
      _ <- applicationRepository.save(block(app))
    } yield Blocked
  }

  def unblockApplication(applicationId: UUID)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def unblock(application: ApplicationData): ApplicationData = {
      application.copy(blocked = false)
    }

    for {
      app <- fetchApp(applicationId)
      _ <- applicationRepository.save(unblock(app))
    } yield Unblocked
  }


  private def fetchApp(applicationId: UUID): Future[ApplicationData] = {
    lazy val notFoundException = new NotFoundException(s"application not found for id: $applicationId")
    applicationRepository.fetch(applicationId).flatMap {
      case None => Future.failed(notFoundException)
      case Some(app) => Future.successful(app)
    }
  }

  private def auditGatekeeperAction(gatekeeperId: String, app: ApplicationData, action: AuditAction,
                                    extra: Map[String, String] = Map.empty)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    auditService.audit(action, AuditHelper.gatekeeperActionDetails(app) ++ extra,
      Map("gatekeeperId" -> gatekeeperId))
  }

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType,
                                 rollback: ApplicationData => Any,
                                 notes: Option[String] = None): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState, notes)
    stateHistoryRepository.insert(stateHistory) andThen {
      case Failure(_) =>
        rollback(snapshotApp)
    }
  }

  val unit: Unit = ()

  val recoverAll: Future[_] => Future[_] = {
    _ recover {
      case e: Throwable => Logger.error(e.getMessage); unit
    }
  }
}
