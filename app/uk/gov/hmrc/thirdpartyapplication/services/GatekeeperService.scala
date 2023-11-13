/*
 * Copyright 2023 HM Revenue & Customs
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

import java.time.{Clock, LocalDateTime}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.{DeleteApplicationRequest, RejectUpliftRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, _}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

@Singleton
class GatekeeperService @Inject() (
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    auditService: AuditService,
    emailConnector: EmailConnector,
    applicationService: ApplicationService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with ClockNow {

  def fetchNonTestingAppsWithSubmittedDate(): Future[List[ApplicationWithUpliftRequest]] = {
    def appError(applicationId: ApplicationId) = new InconsistentDataState(s"App not found for id: ${applicationId.value}")

    def historyError(applicationId: ApplicationId) = new InconsistentDataState(s"History not found for id: ${applicationId.value}")

    def latestUpliftRequestState(histories: List[StateHistory]) = {
      for ((id, history) <- histories.groupBy(_.applicationId))
        yield id -> history.maxBy(_.changedAt)
    }

    val appsFuture         = applicationRepository.fetchStandardNonTestingApps()
    val stateHistoryFuture = stateHistoryRepository.fetchByState(State.PENDING_GATEKEEPER_APPROVAL)
    for {
      apps      <- appsFuture
      appIds     = apps.map(_.id)
      histories <- stateHistoryFuture.map(_.filter(h => appIds.contains(h.applicationId)))
      appsMap    = apps.groupBy(_.id).view.mapValues(_.head).toMap
      historyMap = latestUpliftRequestState(histories)
    } yield DataUtil.zipper(appsMap, historyMap, ApplicationWithUpliftRequest.create, appError, historyError)
  }

  def fetchAppWithHistory(applicationId: ApplicationId): Future[ApplicationWithHistoryResponse] = {
    for {
      app     <- fetchApp(applicationId)
      history <- stateHistoryRepository.fetchByApplicationId(applicationId)
    } yield {
      ApplicationWithHistoryResponse(Application(data = app), history.map(StateHistoryResponse.from))
    }
  }

  def fetchAppStateHistoryById(id: ApplicationId): Future[List[StateHistoryResponse]] = {
    for {
      history <- stateHistoryRepository.fetchByApplicationId(id)
    } yield history.map(StateHistoryResponse.from)
  }

  def fetchAppStateHistories(): Future[Seq[ApplicationStateHistoryResponse]] = {
    for {
      appsWithHistory <- applicationRepository.fetchProdAppStateHistories()
      history          = appsWithHistory.map(a => ApplicationStateHistoryResponse(a.id, a.name, a.version, a.states.map(s => ApplicationStateHistoryResponse.Item(s.state, s.changedAt))))
    } yield history
  }

  def approveUplift(applicationId: ApplicationId, gatekeeperUserId: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def approve(existing: StoredApplication) = existing.copy(state = existing.state.toPendingRequesterVerification(now()))

    def sendEmails(app: StoredApplication) = {
      val requesterEmail   = app.state.requestedByEmailAddress.getOrElse(throw new RuntimeException("no requestedBy email found"))
      val verificationCode = app.state.verificationCode.getOrElse(throw new RuntimeException("no verification code found"))
      val recipients       = app.admins.map(_.emailAddress).filterNot(email => email.equals(LaxEmailAddress(requesterEmail)))

      if (recipients.nonEmpty) emailConnector.sendApplicationApprovedNotification(app.name, recipients)

      emailConnector.sendApplicationApprovedAdminConfirmation(app.name, verificationCode, Set(LaxEmailAddress(requesterEmail)))
    }

    for {
      app    <- fetchApp(applicationId)
      newApp <- applicationRepository.save(approve(app))
      _      <- insertStateHistory(
                  newApp,
                  State.PENDING_REQUESTER_VERIFICATION,
                  Some(State.PENDING_GATEKEEPER_APPROVAL),
                  Actors.GatekeeperUser(gatekeeperUserId),
                  applicationRepository.save
                )
      _       = logger.info(s"UPLIFT04: Approved uplift application:${app.name} appId:${app.id} appState:${app.state.name}" +
                  s" appRequestedByEmailAddress:${app.state.requestedByEmailAddress} gatekeeperUserId:$gatekeeperUserId")
      _       = auditService.auditGatekeeperAction(gatekeeperUserId, newApp, ApplicationUpliftApproved)
      _       = recoverAll(sendEmails(newApp))
    } yield UpliftApproved

  }

  def rejectUplift(applicationId: ApplicationId, request: RejectUpliftRequest)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def reject(existing: StoredApplication) = {
      existing.state.requireState(State.PENDING_GATEKEEPER_APPROVAL, State.TESTING)
      existing.copy(state = existing.state.toTesting(now()))
    }

    def sendEmails(app: StoredApplication, reason: String) =
      emailConnector.sendApplicationRejectedNotification(app.name, app.admins.map(_.emailAddress), reason)

    for {
      app    <- fetchApp(applicationId)
      newApp <- applicationRepository.save(reject(app))
      _      <- insertStateHistory(
                  app,
                  State.TESTING,
                  Some(State.PENDING_GATEKEEPER_APPROVAL),
                  Actors.GatekeeperUser(request.gatekeeperUserId),
                  applicationRepository.save,
                  Some(request.reason)
                )
      _       = logger.info(s"UPLIFT03: Rejected uplift application:${app.name} appId:${app.id} appState:${app.state.name}" +
                  s" appRequestedByEmailAddress:${app.state.requestedByEmailAddress} reason:${request.reason}" +
                  s" gatekeeperUserId:${request.gatekeeperUserId}")
      _       = auditService.auditGatekeeperAction(request.gatekeeperUserId, newApp, ApplicationUpliftRejected, Map("reason" -> request.reason))
      _       = recoverAll(sendEmails(newApp, request.reason))
    } yield UpliftRejected
  }

  def resendVerification(applicationId: ApplicationId, gatekeeperUserId: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def rejectIfNotPendingVerification(existing: StoredApplication) = {
      existing.state.requireState(State.PENDING_REQUESTER_VERIFICATION, State.PENDING_REQUESTER_VERIFICATION)
      existing
    }

    def sendEmails(app: StoredApplication) = {
      val requesterEmail   = app.state.requestedByEmailAddress.getOrElse(throw new RuntimeException("no requestedBy email found"))
      val verificationCode = app.state.verificationCode.getOrElse(throw new RuntimeException("no verification code found"))
      emailConnector.sendApplicationApprovedAdminConfirmation(app.name, verificationCode, Set(LaxEmailAddress(requesterEmail)))
    }

    for {
      app <- fetchApp(applicationId)
      _    = rejectIfNotPendingVerification(app)
      _    = auditService.auditGatekeeperAction(gatekeeperUserId, app, ApplicationVerficationResent)
      _    = recoverAll(sendEmails(app))
    } yield UpliftApproved

  }

  def deleteApplication(applicationId: ApplicationId, request: DeleteApplicationRequest)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def audit(app: StoredApplication): Future[AuditResult] = {
      auditService.auditGatekeeperAction(request.gatekeeperUserId, app, ApplicationDeleted, Map("requestedByEmailAddress" -> request.requestedByEmailAddress.text))
    }
    for {
      _ <- applicationService.deleteApplication(applicationId, Some(request), audit)
    } yield Deleted

  }

  def blockApplication(applicationId: ApplicationId): Future[Blocked] = {
    def block(application: StoredApplication): StoredApplication = {
      application.copy(blocked = true)
    }

    for {
      app <- fetchApp(applicationId)
      _   <- applicationRepository.save(block(app))
    } yield Blocked
  }

  def unblockApplication(applicationId: ApplicationId): Future[Unblocked] = {
    def unblock(application: StoredApplication): StoredApplication = {
      application.copy(blocked = false)
    }

    for {
      app <- fetchApp(applicationId)
      _   <- applicationRepository.save(unblock(app))
    } yield Unblocked
  }

  def fetchAllWithSubscriptions(): Future[List[ApplicationWithSubscriptionsResponse]] = {
    applicationRepository.getAppsWithSubscriptions map {
      _.map(application => ApplicationWithSubscriptionsResponse(application))
    }
  }

  private def fetchApp(applicationId: ApplicationId): Future[StoredApplication] = {
    lazy val notFoundException = new NotFoundException(s"application not found for id: ${applicationId.value}")
    applicationRepository.fetch(applicationId).flatMap {
      case None      => Future.failed(notFoundException)
      case Some(app) => Future.successful(app)
    }
  }

  private def insertStateHistory(
      snapshotApp: StoredApplication,
      newState: State,
      oldState: Option[State],
      actor: Actor,
      rollback: StoredApplication => Any,
      notes: Option[String] = None
    ): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, actor, oldState, notes, LocalDateTime.now(clock))
    stateHistoryRepository.insert(stateHistory) andThen {
      case Failure(_) =>
        rollback(snapshotApp)
    }
  }

  val unit: Unit = ()

  val recoverAll: Future[_] => Future[_] = {
    _ recover {
      case e: Throwable => logger.error(e.getMessage); unit
    }
  }
}
