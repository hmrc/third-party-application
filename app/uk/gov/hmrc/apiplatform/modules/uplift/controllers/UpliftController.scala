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

package uk.gov.hmrc.apiplatform.modules.uplift.controllers

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import play.api.mvc.ControllerComponents

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.uplift.domain.models._
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftService
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.controllers.{ExtraHeadersController, JsErrorResponse, JsonUtils}
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationAlreadyExists, InvalidStateTransition}

object UpliftController {
  import play.api.libs.json.Json

  case class UpliftApplicationRequest(applicationName: String, requestedByEmailAddress: LaxEmailAddress)
  implicit val formatUpliftApplicationRequest = Json.format[UpliftApplicationRequest]
}

@Singleton
class UpliftController @Inject() (upliftService: UpliftService, cc: ControllerComponents)(implicit val ec: ExecutionContext)
    extends ExtraHeadersController(cc)
    with JsonUtils {

  import UpliftController._

  def requestUplift(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[UpliftApplicationRequest] { upliftRequest =>
      upliftService.requestUplift(applicationId, upliftRequest.applicationName, upliftRequest.requestedByEmailAddress)
        .map(_ => NoContent)
    } recover {
      case _: InvalidStateTransition   =>
        PreconditionFailed(JsErrorResponse(INVALID_STATE_TRANSITION, s"Application is not in state '${State.TESTING}'"))
      case e: ApplicationAlreadyExists =>
        Conflict(JsErrorResponse(APPLICATION_ALREADY_EXISTS, s"Application already exists with name: ${e.applicationName}"))
    } recover recovery
  }

  def verifyUplift(verificationCode: String) = Action.async { implicit request =>
    upliftService.verifyUplift(verificationCode) map (_ => NoContent) recover {
      case e: InvalidUpliftVerificationCode => BadRequest(e.getMessage)
    } recover recovery
  }

}
