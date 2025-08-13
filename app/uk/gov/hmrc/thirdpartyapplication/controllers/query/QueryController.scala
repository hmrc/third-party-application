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
import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json._
import play.api.mvc._

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.PaginatedApplications
import uk.gov.hmrc.thirdpartyapplication.controllers.query.ApplicationQuery.{GeneralOpenEndedApplicationQuery, PaginatedApplicationQuery}
import uk.gov.hmrc.thirdpartyapplication.controllers.{ExtraHeadersController, JsonUtils}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class QueryController @Inject() (
    applicationRepository: ApplicationRepository,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with JsonUtils
    with ApplicationLogger {

  private def asBody(errorCode: String, message: Json.JsValueWrapper): JsObject =
    Json.obj(
      "code"    -> errorCode.toString,
      "message" -> message
    )

  def queryDispatcher() = Action.async { implicit request =>
    ParamsValidator.parseAndValidateParams(request.queryString, request.headers.toMap)
      .fold[Future[Result]](
        nel => Future.successful(BadRequest(asBody("INVALID_QUERY", nel.toList))),
        params => apply(ApplicationQuery.attemptToConstructQuery(params))
      )
  }

  private def apply(appQry: ApplicationQuery): Future[Result] = {
    appQry match {
      case q: SingleApplicationQuery => applicationRepository.fetchBySingleApplicationQuery(q).map(oapp => {
          oapp.fold[Result](
            NotFound(asBody("APPLICATION_NOT_FOUND", "No application found for query"))
          )(app => Ok(Json.toJson(StoredApplication.asApplication(app))))
        })

      case q: GeneralOpenEndedApplicationQuery => applicationRepository.fetchByGeneralOpenEndedApplicationQuery(q).map(apps => {
          Ok(Json.toJson(apps.map(StoredApplication.asApplication(_))))
        })

      case q: PaginatedApplicationQuery => applicationRepository.fetchByPaginatedApplicationQuery(q).map(par => {
          Ok(Json.toJson(
            PaginatedApplications(
              par.applications.map(StoredApplication.asApplication),
              q.pagination.pageNbr,
              q.pagination.pageSize,
              par.totals.map(_.total).sum,
              par.matching.map(_.total).sum
            )
          ))
        })
    }
  }
}
