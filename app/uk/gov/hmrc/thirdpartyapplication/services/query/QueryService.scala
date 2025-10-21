/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services.query

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ActorType
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.QueriedApplication
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.SingleApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.util.MetricsTimer

@Singleton
class QueryService @Inject() (
    val metrics: Metrics,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with MetricsTimer {

  def fetchSingleApplicationByQuery(qry: SingleApplicationQuery): Future[Option[QueriedApplication]] = {
    timeFuture("Query Service", "fetchSingleApplicationByQuery") {
      (
        applicationRepository.fetchBySingleApplicationQuery(qry)
      )
    }
  }

  def fetchApplicationsByQuery(qry: GeneralOpenEndedApplicationQuery): Future[List[QueriedApplication]] = {
    timeFuture("Query Service", "fetchApplicationsByQuery") {
      (
        applicationRepository.fetchByGeneralOpenEndedApplicationQuery(qry)
      )
    }
  }

  def fetchPaginatedApplications(qry: PaginatedApplicationQuery): Future[PaginatedApplications] = {
    def patchApplication(app: ApplicationWithCollaborators, deleteHistory: Option[StateHistory]): ApplicationWithCollaborators = {
      app.modify(_.copy(lastActionActor = deleteHistory.map(sh => ActorType.actorType(sh.actor)).getOrElse(ActorType.UNKNOWN)))
    }

    def patchApplications(apps: List[ApplicationWithCollaborators], histories: List[StateHistory]): List[ApplicationWithCollaborators] = {
      apps.map(app => patchApplication(app, histories.find(_.applicationId == app.id)))
    }

    timeFuture("Query Service", "fetchPaginatedApplications") {
      (
        for {
          paginatedApps <- applicationRepository.fetchByPaginatedApplicationQuery(qry)
          apps           = paginatedApps.applications
          ids            = apps.map(_.id)
          histories     <- stateHistoryRepository.fetchDeletedByApplicationIds(ids)
        } yield paginatedApps.copy(applications = patchApplications(apps, histories))
      )
    }
  }
}
