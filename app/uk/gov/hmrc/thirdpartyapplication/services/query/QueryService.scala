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

import cats.data.EitherT

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ActorType
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.SingleApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}

@Singleton
class QueryService @Inject() (
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  def fetchSingleApplicationByQuery(qry: SingleApplicationQuery): Future[Either[Option[ApplicationWithCollaborators], Option[ApplicationWithSubscriptions]]] = {
    EitherT(applicationRepository.fetchBySingleApplicationQuery(qry))
      .bimap(
        _.map(_.asAppWithCollaborators),
        _.map(_.asApplicationWithSubs)
      )
      .value
  }

  def fetchSingleApplication(qry: SingleApplicationQuery): Future[Option[ApplicationWithCollaborators]] = {
    fetchSingleApplicationByQuery(qry).map(_.fold(identity, _.map(_.asAppWithCollaborators)))
  }

  def fetchApplicationsByQuery(qry: GeneralOpenEndedApplicationQuery): Future[Either[List[ApplicationWithCollaborators], List[ApplicationWithSubscriptions]]] = {
    EitherT(applicationRepository.fetchByGeneralOpenEndedApplicationQuery(qry))
      .bimap(
        _.map(_.asAppWithCollaborators),
        _.map(_.asApplicationWithSubs)
      )
      .value
  }

  def fetchApplications(qry: GeneralOpenEndedApplicationQuery): Future[List[ApplicationWithCollaborators]] = {
    fetchApplicationsByQuery(qry).map(_.fold(identity, _.map(_.asAppWithCollaborators)))
  }

  def fetchApplicationsWithSubscriptions(qry: GeneralOpenEndedApplicationQuery): Future[List[ApplicationWithSubscriptions]] = {
    fetchApplicationsByQuery(qry).map(_.getOrElse(Nil))
  }

  // def fetchPaginatedApplications(qry: PaginatedApplicationQuery): Future[PaginatedApplications] = {
  //   val fPaginatedApps = applicationRepository.fetchByPaginatedApplicationQuery(qry)
  //     .map(pad =>
  //       PaginatedApplications(
  //         pad.applications.map(_.asAppWithCollaborators),
  //         qry.pagination.pageNbr,
  //         qry.pagination.pageSize,
  //         pad.countOfAllApps.map(_.total).sum,
  //         pad.countOfMatchingApps.map(_.total).sum
  //       )
  //     )

  //   val fIds = fPaginatedApps.map(_.applications.map(_.id))

  //   def patchApplication(app: ApplicationWithCollaborators, deleteHistory: Option[StateHistory]) = {
  //     app.modify(_.copy(lastActionActor = deleteHistory.map(sh => ActorType.actorType(sh.actor)).getOrElse(ActorType.UNKNOWN)))
  //   }

  //   val fAppHistories: Future[List[StateHistory]]                 =
  //     fIds.flatMap(ids => stateHistoryRepository.fetchDeletedByApplicationIds(ids))

  //   fPaginatedApps.zipWith(fAppHistories) {
  //     case (pApps, appHistories) => pApps.copy(applications = pApps.applications.map(app => patchApplication(app, appHistories.find(ah => ah.applicationId == app.id))))
  //   }
  // }

  def fetchPaginatedApplications(qry: PaginatedApplicationQuery): Future[PaginatedApplications] = {
    def patchApplication(app: ApplicationWithCollaborators, deleteHistory: Option[StateHistory]): ApplicationWithCollaborators = {
      app.modify(_.copy(lastActionActor = deleteHistory.map(sh => ActorType.actorType(sh.actor)).getOrElse(ActorType.UNKNOWN)))
    }

    def patchApplications(apps: List[ApplicationWithCollaborators], histories: List[StateHistory]): List[ApplicationWithCollaborators] = {
      apps.map(app => patchApplication(app, histories.find(_.applicationId == app.id)))
    }

    for {
      paginatedApps <- applicationRepository.fetchByPaginatedApplicationQuery(qry)
      apps           = paginatedApps.applications
      ids            = apps.map(_.id)
      histories     <- stateHistoryRepository.fetchDeletedByApplicationIds(ids)
    } yield paginatedApps.copy(applications = patchApplications(apps, histories))
  }
}
