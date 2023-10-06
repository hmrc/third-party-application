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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import play.api.libs.json.{JsValue, Json}
import play.api.mvc.ControllerComponents

import uk.gov.hmrc.apiplatform.modules.approvals.controllers.actions.{ApprovalsActionBuilders, JsonErrorResponse}
import uk.gov.hmrc.apiplatform.modules.approvals.services.{GrantApprovalsService, RequestApprovalsService}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.controllers.{ExtraHeadersController, JsonUtils}
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationDataService

object ApprovalsController {
  case class RequestApprovalRequest(requestedByName: String, requestedByEmailAddress: String)
  implicit val readsRequestApprovalRequest = Json.reads[RequestApprovalRequest]

  case class DeclinedRequest(gatekeeperUserName: String, reasons: String)
  implicit val readsDeclinedRequest = Json.reads[DeclinedRequest]

  case class GrantedRequest(gatekeeperUserName: String, warnings: Option[String], escalatedTo: Option[String])
  implicit val readsGrantedRequest = Json.reads[GrantedRequest]

  case class TouUpliftRequest(gatekeeperUserName: String, reasons: String)
  implicit val readsTouUpliftRequest = Json.reads[TouUpliftRequest]

  case class TouGrantedRequest(gatekeeperUserName: String, reasons: String, escalatedTo: Option[String])
  implicit val readsTouGrantedRequest = Json.reads[TouGrantedRequest]
}

@Singleton
class ApprovalsController @Inject() (
    val applicationDataService: ApplicationDataService,
    val submissionService: SubmissionsService,
    requestApprovalsService: RequestApprovalsService,
    grantApprovalService: GrantApprovalsService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with ApprovalsActionBuilders
    with JsonUtils
    with JsonErrorResponse {

  import ApprovalsController._

  def requestApproval(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import RequestApprovalsService._

    withJsonBodyFromAnyContent[RequestApprovalRequest] { requestApprovalRequest =>
      requestApprovalsService.requestApproval(request.application, request.submission, requestApprovalRequest.requestedByName, requestApprovalRequest.requestedByEmailAddress).map(
        _ match {
          case ApprovalAccepted(application)                        => Ok(Json.toJson(ApplicationResponse(application)))
          case ApprovalRejectedDueToIncorrectSubmissionState(state) =>
            PreconditionFailed(asJsonError("SUBMISSION_IN_INCORRECT_STATE", s"Submission for $applicationId is in an incorrect state of #'$state'"))
          case ApprovalRejectedDueToDuplicateName(name)             => Conflict(asJsonError("APPLICATION_ALREADY_EXISTS", s"An application already exists for the name '$name' "))
          case ApprovalRejectedDueToIllegalName(name)               =>
            PreconditionFailed(asJsonError("INVALID_APPLICATION_NAME", s"The application name '$name' contains words that are prohibited"))
          case ApprovalRejectedDueToIncorrectApplicationState       => PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.TESTING}'"))
        }
      )
        .recover(recovery)
    }
  }

  def grant(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[GrantedRequest] { grantedRequest =>
      grantApprovalService.grant(request.application, request.submission, grantedRequest.gatekeeperUserName, grantedRequest.warnings, grantedRequest.escalatedTo)
        .map(_ match {
          case Actioned(application)                  => Ok(Json.toJson(ApplicationResponse(application)))
          case RejectedDueToIncorrectSubmissionState  => PreconditionFailed(asJsonError("NOT_IN_SUBMITTED_STATE", s"Submission for $applicationId was not in a submitted state"))
          case RejectedDueToIncorrectApplicationState =>
            PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PENDING_GATEKEEPER_APPROVAL}'"))
          case RejectedDueToIncorrectApplicationData  => PreconditionFailed(asJsonError("APPLICATION_DATA_IS_INCORRECT", "Application does not have the expected data"))
        })
    }
      .recover(recovery)
  }

  def grantWithWarningsForTouUplift(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[TouUpliftRequest] { upliftRequest =>
      grantApprovalService.grantWithWarningsForTouUplift(request.application, request.submission, upliftRequest.gatekeeperUserName, upliftRequest.reasons)
        .map(_ match {
          case Actioned(application)                  => Ok(Json.toJson(ApplicationResponse(application)))
          case RejectedDueToIncorrectSubmissionState  => PreconditionFailed(asJsonError("NOT_IN_WARNINGS_STATE", s"Submission for $applicationId was not in a warnings state"))
          case RejectedDueToIncorrectApplicationState =>
            PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PRODUCTION}'"))
          case RejectedDueToIncorrectApplicationData  => PreconditionFailed(asJsonError("APPLICATION_DATA_IS_INCORRECT", "Application does not have the expected data"))
        })
    }
      .recover(recovery)
  }

  def grantForTouUplift(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[TouGrantedRequest] { grantedRequest =>
      grantApprovalService.grantForTouUplift(request.application, request.submission, grantedRequest.gatekeeperUserName, grantedRequest.reasons, grantedRequest.escalatedTo)
        .map(_ match {
          case Actioned(application)                  => Ok(Json.toJson(ApplicationResponse(application)))
          case RejectedDueToIncorrectSubmissionState  =>
            PreconditionFailed(asJsonError("NOT_IN_GRANTED_WITH_WARNINGS_STATE", s"Submission for $applicationId was not in a granted with warnings state"))
          case RejectedDueToIncorrectApplicationState =>
            PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PRODUCTION}'"))
          case RejectedDueToIncorrectApplicationData  => PreconditionFailed(asJsonError("APPLICATION_DATA_IS_INCORRECT", "Application does not have the expected data"))
        })
    }
      .recover(recovery)
  }

  def declineForTouUplift(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[TouUpliftRequest] { upliftRequest =>
      grantApprovalService.declineForTouUplift(request.application, request.submission, upliftRequest.gatekeeperUserName, upliftRequest.reasons)
        .map(_ match {
          case Actioned(application)                  => Ok(Json.toJson(ApplicationResponse(application)))
          case RejectedDueToIncorrectSubmissionState  => PreconditionFailed(asJsonError("NOT_IN_WARNINGS_STATE", s"Submission for $applicationId was not in a warnings state"))
          case RejectedDueToIncorrectApplicationState =>
            PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PRODUCTION}'"))
          case RejectedDueToIncorrectApplicationData  => PreconditionFailed(asJsonError("APPLICATION_DATA_IS_INCORRECT", "Application does not have the expected data"))
        })
    }
      .recover(recovery)
  }

  private def asJsonError(errorCode: String, message: String): JsValue =
    Json.toJson(
      Json.obj(
        "code"    -> errorCode,
        "message" -> message
      )
    )
}
