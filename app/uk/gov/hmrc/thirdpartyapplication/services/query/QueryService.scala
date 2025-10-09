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

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.QueriedApplication
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.SingleApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class QueryService @Inject() (
    applicationRepository: ApplicationRepository
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  def fetchSingleApplicationByQuery(qry: SingleApplicationQuery): Future[Option[QueriedApplication]] = {
    EitherT(applicationRepository.fetchBySingleApplicationQuery(qry))
      .fold(
        _.map(_.asQueriedApplication),
        _.map(_.asQueriedApplication)
      )
  }

  def fetchApplicationsByQuery(qry: GeneralOpenEndedApplicationQuery): Future[List[QueriedApplication]] = {
    EitherT(applicationRepository.fetchByGeneralOpenEndedApplicationQuery(qry))
      .fold(
        _.map(_.asQueriedApplication),
        _.map(_.asQueriedApplication)
      )
  }

  def fetchPaginatedApplications(qry: PaginatedApplicationQuery): Future[PaginatedApplications] = {
    applicationRepository.fetchByPaginatedApplicationQuery(qry)
      .map(pad =>
        PaginatedApplications(
          pad.applications.map(_.asAppWithCollaborators),
          qry.pagination.pageNbr,
          qry.pagination.pageSize,
          pad.countOfAllApps.map(_.total).sum,
          pad.countOfMatchingApps.map(_.total).sum
        )
      )
  }
}
