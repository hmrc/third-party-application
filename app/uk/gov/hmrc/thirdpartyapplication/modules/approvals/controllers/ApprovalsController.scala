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

package uk.gov.hmrc.thirdpartyapplication.modules.approvals.controllers

import scala.concurrent.ExecutionContext
import play.api.mvc.ControllerComponents
import javax.inject.Inject
import javax.inject.Singleton
import uk.gov.hmrc.thirdpartyapplication.controllers.JsonUtils
import uk.gov.hmrc.thirdpartyapplication.controllers.ExtraHeadersController
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationAlreadyExists, InvalidStateTransition}
import uk.gov.hmrc.thirdpartyapplication.controllers.JsErrorResponse
import uk.gov.hmrc.thirdpartyapplication.modules.approvals.services.ApplicationApprovalsService

object ApprovalsController {
  case class RequestApprovalRequest(requestedByEmailAddress: String)

  implicit val formatRequestApprovalRequest = Json.reads[RequestApprovalRequest]
}

@Singleton
class ApprovalsController @Inject()(
  applicationApprovalsService: ApplicationApprovalsService,
  cc: ControllerComponents
)
(
  implicit val ec: ExecutionContext
) extends ExtraHeadersController(cc)  
    with JsonUtils {

  import ApprovalsController._

  def requestApproval(applicationId: ApplicationId) = Action.async(parse.json) { implicit request => 
    withJsonBody[RequestApprovalRequest] { requestApprovalRequest => 
      applicationApprovalsService
        .requestApproval(applicationId, requestApprovalRequest.requestedByEmailAddress)
        .map(_ => NoContent)
        .recover {
          case _: InvalidStateTransition =>
            PreconditionFailed(JsErrorResponse(ErrorCode.INVALID_STATE_TRANSITION, s"Application is not in state '${State.TESTING}'"))
          case e: ApplicationAlreadyExists =>
            Conflict(JsErrorResponse(ErrorCode.APPLICATION_ALREADY_EXISTS, s"Application already exists with name: ${e.applicationName}"))
        }
      }
      .recover(recovery)
    }
}
