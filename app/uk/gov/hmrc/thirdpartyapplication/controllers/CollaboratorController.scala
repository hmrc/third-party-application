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

package uk.gov.hmrc.thirdpartyapplication.controllers

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json.toJson
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import scala.concurrent.ExecutionContext

import uk.gov.hmrc.thirdpartyapplication.domain.models._
import play.api.libs.json.Json

private[controllers] case class SearchCollaboratorsRequest(apiContext: ApiContext, apiVersion: ApiVersion, partialEmailMatch: Option[String])

private[controllers] object SearchCollaboratorsRequest {
  implicit val readsSearchCollaboratorsRequest = Json.reads[SearchCollaboratorsRequest]
}

@Singleton
class CollaboratorController @Inject() (
    val applicationService: ApplicationService,
    subscriptionService: SubscriptionService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) with JsonUtils {

  override implicit def hc(implicit request: RequestHeader) = {
    def header(key: String) = request.headers.get(key) map (key -> _)

    val extraHeaders = List(header(LOGGED_IN_USER_NAME_HEADER), header(LOGGED_IN_USER_EMAIL_HEADER), header(SERVER_TOKEN_HEADER)).flatten
    super.hc.withExtraHeaders(extraHeaders: _*)
  }

  def searchCollaborators() = Action.async(parse.json) { implicit request =>
    withJsonBody[SearchCollaboratorsRequest] { searchRequest =>
      subscriptionService.searchCollaborators(searchRequest.apiContext, searchRequest.apiVersion, searchRequest.partialEmailMatch).map(toJson(_)).map(Ok(_))
    }
  }
}
