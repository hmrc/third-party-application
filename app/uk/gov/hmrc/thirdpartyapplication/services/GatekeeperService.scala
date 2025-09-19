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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQueries
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.DeleteApplicationRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, _}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{GatekeeperAppSubsResponse, StoredApplication}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.query.QueryService

@Singleton
class GatekeeperService @Inject() (
    queryService: QueryService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    auditService: AuditService,
    emailConnector: EmailConnector,
    applicationService: ApplicationService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with ClockNow {

  def fetchNonTestingAppsWithSubmittedDate(): Future[List[ApplicationWithUpliftRequest]] = {
    def appError(applicationId: ApplicationId) = new InconsistentDataState(s"App not found for id: ${applicationId}")

    def historyError(applicationId: ApplicationId) = new InconsistentDataState(s"History not found for id: ${applicationId}")

    def latestUpliftRequestState(histories: List[StateHistory]) = {
      for ((id, history) <- histories.groupBy(_.applicationId))
        yield id -> history.maxBy(_.changedAt)
    }

    val appsFuture         = queryService.fetchApplications(ApplicationQueries.standardNonTestingApps)
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
      ApplicationWithHistoryResponse(app.asAppWithCollaborators, history.map(StateHistoryResponse.from))
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

  def deleteApplication(applicationId: ApplicationId, request: DeleteApplicationRequest)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def audit(app: StoredApplication): Future[AuditResult] = {
      auditService.auditGatekeeperAction(request.gatekeeperUserId, app, ApplicationDeleted, Map("requestedByEmailAddress" -> request.requestedByEmailAddress.text))
    }
    for {
      _ <- applicationService.deleteApplication(applicationId, Some(request), audit)
    } yield Deleted

  }

  def fetchAllWithSubscriptions(): Future[List[GatekeeperAppSubsResponse]] = {
    applicationRepository.getAppsWithSubscriptions
  }

  private def fetchApp(applicationId: ApplicationId): Future[StoredApplication] = {
    lazy val notFoundException = new NotFoundException(s"application not found for id: ${applicationId}")
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
