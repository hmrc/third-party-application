/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.uplift.controllers

import scala.concurrent.ExecutionContext
import play.api.mvc.ControllerComponents
import javax.inject.Inject
import javax.inject.Singleton
import uk.gov.hmrc.thirdpartyapplication.controllers.JsonUtils
import uk.gov.hmrc.thirdpartyapplication.controllers.ExtraHeadersController
import uk.gov.hmrc.thirdpartyapplication.controllers.JsErrorResponse
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationAlreadyExists
import uk.gov.hmrc.thirdpartyapplication.models.InvalidStateTransition
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.modules.uplift.services.ApplicationUpliftService
import uk.gov.hmrc.thirdpartyapplication.modules.uplift.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.uplift.domain.services.JsonFormatters._

@Singleton
class ApplicationUpliftController @Inject()(
  applicationUpliftService: ApplicationUpliftService,
  cc: ControllerComponents
)
(
  implicit val ec: ExecutionContext
) extends ExtraHeadersController(cc)  
    with JsonUtils {

  def requestUplift(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[UpliftApplicationRequest] { upliftRequest =>
      applicationUpliftService.requestUplift(applicationId, upliftRequest.applicationName, upliftRequest.requestedByEmailAddress)
        .map(_ => NoContent)
    } recover {
      case _: InvalidStateTransition =>
        PreconditionFailed(JsErrorResponse(INVALID_STATE_TRANSITION, s"Application is not in state '${State.TESTING}'"))
      case e: ApplicationAlreadyExists =>
        Conflict(JsErrorResponse(APPLICATION_ALREADY_EXISTS, s"Application already exists with name: ${e.applicationName}"))
    } recover recovery
  }

  
  def verifyUplift(verificationCode: String) = Action.async { implicit request =>
    applicationUpliftService.verifyUplift(verificationCode) map (_ => NoContent) recover {
      case e: InvalidUpliftVerificationCode => BadRequest(e.getMessage)
    } recover recovery
  }

}
