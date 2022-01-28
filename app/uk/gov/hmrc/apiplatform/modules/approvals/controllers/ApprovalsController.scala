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

import scala.concurrent.ExecutionContext
import play.api.mvc.ControllerComponents
import javax.inject.Inject
import javax.inject.Singleton
import uk.gov.hmrc.thirdpartyapplication.controllers.JsonUtils
import uk.gov.hmrc.thirdpartyapplication.controllers.ExtraHeadersController
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import play.api.libs.json.Json
import uk.gov.hmrc.apiplatform.modules.approvals.services.RequestApprovalsService
import uk.gov.hmrc.apiplatform.modules.approvals.services.RequestApprovalsService._
import uk.gov.hmrc.apiplatform.modules.approvals.services.DeclineApprovalsService
import play.api.libs.json.JsValue
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import akka.http.scaladsl.model.HttpHeader

object ApprovalsController {
  case class RequestApprovalRequest(requestedByEmailAddress: String)
  implicit val readsRequestApprovalRequest = Json.reads[RequestApprovalRequest]

  case class DeclinedRequest(name: String, reasons: String)
  implicit val readsDeclinedRequest = Json.reads[DeclinedRequest]
}

@Singleton
class ApprovalsController @Inject()(
  requestApprovalsService: RequestApprovalsService,
  declineApprovalService: DeclineApprovalsService,
  cc: ControllerComponents
)
(
  implicit val ec: ExecutionContext
) extends ExtraHeadersController(cc)  
    with JsonUtils {

  import ApprovalsController._

  def requestApproval(applicationId: ApplicationId) = Action.async(parse.json) { implicit request => 
    withJsonBody[RequestApprovalRequest] { requestApprovalRequest => 
      requestApprovalsService.requestApproval(applicationId, requestApprovalRequest.requestedByEmailAddress).map( _ match {
        case ApprovalAccepted(application)                                    => Ok(Json.toJson(ApplicationResponse(application)))
        case ApprovalRejectedDueToNoSuchApplication | 
              ApprovalRejectedDueToNoSuchSubmission                           => BadRequest(asJsonError("INVALID_ARGS", s"ApplicationId $applicationId is invalid"))
        case ApprovalRejectedDueToIncompleteSubmission                        => PreconditionFailed(asJsonError("INCOMPLETE_SUBMISSION", s"Submission for $applicationId is incomplete"))
        case ApprovalRejectedDueToAlreadySubmitted                            => PreconditionFailed(asJsonError("ALREADY_SUBMITTED", s"Submission for $applicationId was already submitted"))
        case ApprovalRejectedDueToDuplicateName(name)                         => Conflict(asJsonError("APPLICATION_ALREADY_EXISTS", s"An application already exists for the name '$name' ")) 
        case ApprovalRejectedDueToIllegalName(name)                           => PreconditionFailed(asJsonError("INVALID_APPLICATION_NAME", s"The application name '$name' contains words that are prohibited")) 
        case ApprovalRejectedDueToIncorrectState                              => PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.TESTING}'")) 
      })
      .recover(recovery)
    }
  }

  def decline(applicationId: ApplicationId) = Action.async(parse.json) { implicit request => 
    withJsonBody[DeclinedRequest] { declinedRequest => 
      declineApprovalService.decline(applicationId, declinedRequest.name, declinedRequest.reasons)
      .map( _ match {
        // TODO
        case _ => Ok
      })
    }
    .recover(recovery)
  }

  def grant(applicationId: ApplicationId) = ???

  private def asJsonError(errorCode: String, message: String): JsValue = 
    Json.toJson(
      Json.obj(
        "code" -> errorCode,
        "message" -> message
      )
    )
}
