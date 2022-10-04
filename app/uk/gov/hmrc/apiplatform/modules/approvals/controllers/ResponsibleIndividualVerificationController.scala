/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers

import play.api.mvc.ControllerComponents

import javax.inject.Inject
import javax.inject.Singleton
import uk.gov.hmrc.thirdpartyapplication.controllers.JsonUtils
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerification
import uk.gov.hmrc.apiplatform.modules.approvals.domain.services.ResponsibleIndividualVerificationFrontendJsonFormatters
import play.api.libs.json.Json
import play.api.mvc.Results

import scala.concurrent.ExecutionContext
import uk.gov.hmrc.apiplatform.modules.approvals.services.ResponsibleIndividualVerificationService
import uk.gov.hmrc.apiplatform.modules.approvals.controllers.actions.JsonErrorResponse

object ResponsibleIndividualVerificationController {
  case class ResponsibleIndividualVerificationRequest(code: String)
  implicit val readsResponsibleIndividualVerificationRequest = Json.reads[ResponsibleIndividualVerificationRequest]

  case class ErrorMessage(message: String)
  implicit val writesErrorMessage = Json.writes[ErrorMessage]
}

@Singleton
class ResponsibleIndividualVerificationController @Inject() (
    val responsibleIndividualVerificationService: ResponsibleIndividualVerificationService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) with ResponsibleIndividualVerificationFrontendJsonFormatters
    with JsonUtils
    with JsonErrorResponse {

  def getVerification(code: String) = Action.async { implicit request =>
    lazy val failed = NotFound(Results.EmptyContent())
    val success     = (responsibleIndividualVerification: ResponsibleIndividualVerification) => Ok(Json.toJson(responsibleIndividualVerification))

    responsibleIndividualVerificationService.getVerification(code).map(_.fold(failed)(success))
  }
}
