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

import play.api.libs.json.{JsValue, Json, Reads}
import play.api.mvc.ControllerComponents

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.approvals.controllers.actions.{ApprovalsActionBuilders, JsonErrorResponse}
import uk.gov.hmrc.apiplatform.modules.approvals.services.GrantApprovalsService
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.controllers.{ExtraHeadersController, JsonUtils}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationDataService

object ApprovalsController {
  case class TouUpliftRequest(gatekeeperUserName: String, reasons: String)
  implicit val readsTouUpliftRequest: Reads[TouUpliftRequest] = Json.reads[TouUpliftRequest]

  case class TouDeleteRequest(gatekeeperUserName: String)
  implicit val readsTouDeleteRequest: Reads[TouDeleteRequest] = Json.reads[TouDeleteRequest]
}

@Singleton
class ApprovalsController @Inject() (
    val applicationDataService: ApplicationDataService,
    val submissionService: SubmissionsService,
    grantApprovalService: GrantApprovalsService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with ApprovalsActionBuilders
    with JsonUtils
    with JsonErrorResponse {

  import ApprovalsController._

  def grantWithWarningsForTouUplift(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[TouUpliftRequest] { upliftRequest =>
      grantApprovalService.grantWithWarningsForTouUplift(request.application, request.submission, upliftRequest.gatekeeperUserName, upliftRequest.reasons)
        .map(_ match {
          case Actioned(application)                  => Ok(Json.toJson(StoredApplication.asApplication(application)))
          case RejectedDueToIncorrectSubmissionState  => PreconditionFailed(asJsonError("NOT_IN_WARNINGS_STATE", s"Submission for $applicationId was not in a warnings state"))
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
          case Actioned(application)                  => Ok(Json.toJson(StoredApplication.asApplication(application)))
          case RejectedDueToIncorrectSubmissionState  => PreconditionFailed(asJsonError("NOT_IN_WARNINGS_STATE", s"Submission for $applicationId was not in a warnings state"))
          case RejectedDueToIncorrectApplicationState =>
            PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PRODUCTION}'"))
          case RejectedDueToIncorrectApplicationData  => PreconditionFailed(asJsonError("APPLICATION_DATA_IS_INCORRECT", "Application does not have the expected data"))
        })
    }
      .recover(recovery)
  }

  def resetForTouUplift(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[TouUpliftRequest] { upliftRequest =>
      grantApprovalService.resetForTouUplift(request.application, request.submission, upliftRequest.gatekeeperUserName, upliftRequest.reasons)
        .map(_ match {
          case Actioned(application)                  => Ok(Json.toJson(StoredApplication.asApplication(application)))
          case RejectedDueToIncorrectSubmissionState  =>
            PreconditionFailed(asJsonError("SUBMISSION_IN_INCORRECT_STATE", s"Submission for $applicationId was not in the expected state"))
          case RejectedDueToIncorrectApplicationState =>
            PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PRODUCTION}'"))
          case RejectedDueToIncorrectApplicationData  => PreconditionFailed(asJsonError("APPLICATION_DATA_IS_INCORRECT", "Application does not have the expected data"))
        })
    }
      .recover(recovery)
  }

  def deleteTouUplift(applicationId: ApplicationId) = withApplicationAndSubmission(applicationId) { implicit request =>
    import GrantApprovalsService._

    withJsonBodyFromAnyContent[TouDeleteRequest] { deleteRequest =>
      grantApprovalService.deleteTouUplift(request.application, request.submission, deleteRequest.gatekeeperUserName)
        .map(_ match {
          case Actioned(application)                  => Ok(Json.toJson(StoredApplication.asApplication(application)))
          case RejectedDueToIncorrectApplicationState =>
            PreconditionFailed(asJsonError("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state '${State.PRODUCTION}'"))
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
