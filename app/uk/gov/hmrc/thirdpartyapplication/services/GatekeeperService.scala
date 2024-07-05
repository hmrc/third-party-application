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

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.DeleteApplicationRequest
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

  @deprecated
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
      _    = auditService.auditGatekeeperAction(gatekeeperUserId, app, ApplicationVerificationResent)
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

  @deprecated
  def blockApplication(applicationId: ApplicationId): Future[Blocked] = {
    def block(application: StoredApplication): StoredApplication = {
      application.copy(blocked = true)
    }

    for {
      app <- fetchApp(applicationId)
      _   <- applicationRepository.save(block(app))
    } yield Blocked
  }

  @deprecated
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

  val unit: Unit = ()

  val recoverAll: Future[_] => Future[_] = {
    _ recover {
      case e: Throwable => logger.error(e.getMessage); unit
    }
  }
}
