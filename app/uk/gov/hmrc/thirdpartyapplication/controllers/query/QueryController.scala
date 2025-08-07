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

package uk.gov.hmrc.thirdpartyapplication.controllers.query

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext}

import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.thirdpartyapplication.controllers.{JsonUtils, ExtraHeadersController}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.controllers.query.ApplicationQuery.PaginatedApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.controllers.query.ApplicationQuery.GeneralOpenEndedApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

@Singleton
class QueryController @Inject() (
    applicationRepository: ApplicationRepository,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with JsonUtils
    with ApplicationLogger {

  // private val E = EitherTHelper.make[String]

  def queryDispatcher() = Action.async { implicit request =>
    ApplicationQuery.attemptToConstructQuery(request.queryString, request.headers.toMap)
    .fold[Future[Result]]( nel =>
      Future.successful(BadRequest(Json.toJson(nel.toList)))
    ,
      appQry => apply(appQry)
    )
  }

  def apply(appQry: ApplicationQuery): Future[Result] = {
    appQry match {
      case q: SingleApplicationQuery => applicationRepository.fetchBySingleApplicationQuery(q).map( oapp => {
        oapp.fold[Result](
          NotFound("No application found for query")
        )(
          app => Ok(Json.toJson(StoredApplication.asApplication(app)))
        )}
      )
      
      case q: GeneralOpenEndedApplicationQuery => applicationRepository.fetchByGeneralOpenEndedApplicationQuery(q).map(apps => {
        Ok(Json.toJson(apps.map(StoredApplication.asApplication(_))))
      })

      case q: PaginatedApplicationQuery => applicationRepository.fetchByPaginatedApplicationQuery(q).map( par => {
        Ok(Results.EmptyContent())  // TODO
      })
    }
  }
}
