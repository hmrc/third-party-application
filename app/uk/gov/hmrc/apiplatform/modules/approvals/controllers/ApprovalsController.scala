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
import uk.gov.hmrc.thirdpartyapplication.controllers.ExtraHeadersController
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import play.api.libs.json.Json
import uk.gov.hmrc.apiplatform.modules.approvals.services.RequestApprovalsService
import uk.gov.hmrc.apiplatform.modules.approvals.services.DeclineApprovalsService
import play.api.libs.json.JsValue
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future.successful
import uk.gov.hmrc.apiplatform.modules.approvals.controllers.actions.ApprovalsActionBuilders
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationDataService
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.approvals.services.GrantApprovalsService

object ApprovalsController {
  case class RequestApprovalRequest(requestedByEmailAddress: String)
  implicit val readsRequestApprovalRequest = Json.reads[RequestApprovalRequest]

  case class DeclinedRequest(name: String, reasons: String)
  implicit val readsDeclinedRequest = Json.reads[DeclinedRequest]

  case class GrantedRequest(name: String)
  implicit val readsGrantedRequest = Json.reads[GrantedRequest]
}

@Singleton
class ApprovalsController @Inject()(
  val applicationDataService: ApplicationDataService,
  val submissionService: SubmissionsService,
  requestApprovalsService: RequestApprovalsService,
  declineApprovalService: DeclineApprovalsService,
  grantApprovalService: GrantApprovalsService,
  cc: ControllerComponents
)
(
  implicit val ec: ExecutionContext
) extends ExtraHeadersController(cc)
  with ApprovalsActionBuilders  
    with JsonUtils {

  import ApprovalsController._

  def requestApproval(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request => 
    import RequestApprovalsService._

    withJsonBodyFromAnyContent[RequestApprovalRequest] { requestApprovalRequest => 
      requestApprovalsService.requestApproval(request.application, request.extSubmission, requestApprovalRequest.requestedByEmailAddress).map( _ match {
        case ApprovalAccepted(application)                                    => Ok(Json.toJson(ApplicationResponse(application)))
        case ApprovalRejectedDueToIncompleteSubmission                        => PreconditionFailed(asJsonError("INCOMPLETE_SUBMISSION", s"Submission for $applicationId is incomplete"))
        case ApprovalRejectedDueToAlreadySubmitted                            => PreconditionFailed(asJsonError("ALREADY_SUBMITTED", s"Submission for $applicationId was already submitted"))
        case ApprovalRejectedDueToDuplicateName(name)                         => Conflict(asJsonError("APPLICATION_ALREADY_EXISTS", s"An application already exists for the name '$name' ")) 
        case ApprovalRejectedDueToIllegalName(name)                           => PreconditionFailed(asJsonError("INVALID_APPLICATION_NAME", s"The application name '$name' contains words that are prohibited")) 
        case ApprovalRejectedDueToIncorrectApplicationState                              => PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.TESTING}'"))
        
      })
      .recover(recovery)
    }
  }

  def decline(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request => 
    import DeclineApprovalsService._

    withJsonBodyFromAnyContent[DeclinedRequest] { declinedRequest => 
      declineApprovalService.decline(request.application, request.extSubmission, declinedRequest.name, declinedRequest.reasons)
      .map( _ match {
        case Actioned(application)                                            => Ok(Json.toJson(ApplicationResponse(application)))
        case RejectedDueToNoSuchApplication  | 
              RejectedDueToNoSuchSubmission                                   => BadRequest(asJsonError("INVALID_ARGS", s"ApplicationId $applicationId is invalid"))
        case RejectedDueToIncompleteSubmission                                => PreconditionFailed(asJsonError("INCOMPLETE_SUBMISSION", s"Submission for $applicationId is incomplete"))
        case RejectedDueToIncorrectSubmissionState                            => PreconditionFailed(asJsonError("NOT_IN_SUBMITTED_STATE", s"Submission for $applicationId was not in a submitted state"))
        case RejectedDueToIncorrectApplicationState                           => PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PENDING_GATEKEEPER_APPROVAL}'")) 
      })
    }
    .recover(recovery)
  }

  def grant(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[GrantedRequest] { grantedRequest => 
      grantApprovalService.grant(request.application, request.extSubmission, grantedRequest.name)
      .map( _ match {
        case Actioned(application)                                            => Ok(Json.toJson(ApplicationResponse(application)))
        case RejectedDueToNoSuchApplication  | 
              RejectedDueToNoSuchSubmission                                   => BadRequest(asJsonError("INVALID_ARGS", s"ApplicationId $applicationId is invalid"))
        case RejectedDueToIncompleteSubmission                                => PreconditionFailed(asJsonError("INCOMPLETE_SUBMISSION", s"Submission for $applicationId is incomplete"))
        case RejectedDueToIncorrectSubmissionState                            => PreconditionFailed(asJsonError("NOT_IN_SUBMITTED_STATE", s"Submission for $applicationId was not in a submitted state"))
        case RejectedDueToIncorrectApplicationState                           => PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PENDING_GATEKEEPER_APPROVAL}'")) 
      })
    }
    .recover(recovery)
  }

  private def asJsonError(errorCode: String, message: String): JsValue = 
    Json.toJson(
      Json.obj(
        "code" -> errorCode,
        "message" -> message
      )
    )
}
