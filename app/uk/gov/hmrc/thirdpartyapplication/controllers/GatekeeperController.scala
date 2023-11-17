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
import scala.concurrent.ExecutionContext

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.InvalidStateTransition
import uk.gov.hmrc.apiplatform.modules.gkauth.controllers.actions._
import uk.gov.hmrc.apiplatform.modules.gkauth.services.{LdapGatekeeperRoleAuthorisationService, StrideGatekeeperRoleAuthorisationService}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.controllers.actions.TermsOfUseInvitationActionBuilders
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services._

@Singleton
class GatekeeperController @Inject() (
    val applicationService: ApplicationService,
    val ldapGatekeeperRoleAuthorisationService: LdapGatekeeperRoleAuthorisationService,
    val strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService,
    gatekeeperService: GatekeeperService,
    val termsOfUseInvitationService: TermsOfUseInvitationService,
    val applicationDataService: ApplicationDataService,
    val submissionsService: SubmissionsService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc)
    with JsonUtils
    with AnyGatekeeperRoleAuthorisationAction
    with OnlyStrideGatekeeperRoleAuthoriseAction
    with TermsOfUseInvitationActionBuilders {

  private lazy val badStateResponse = PreconditionFailed(
    JsErrorResponse(INVALID_STATE_TRANSITION, "Application is not in state 'PENDING_GATEKEEPER_APPROVAL'")
  )

  private lazy val badResendResponse = PreconditionFailed(
    JsErrorResponse(INVALID_STATE_TRANSITION, "Application is not in state 'PENDING_REQUESTER_VERIFICATION'")
  )

  def approveUplift(id: ApplicationId) = requiresAuthentication().async(parse.json) {
    implicit request =>
      withJsonBody[ApproveUpliftRequest] { approveUpliftPayload =>
        gatekeeperService.approveUplift(id, approveUpliftPayload.gatekeeperUserId)
          .map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badStateResponse
      } recover recovery
  }

  def rejectUplift(id: ApplicationId) = requiresAuthentication().async(parse.json) {
    implicit request =>
      withJsonBody[RejectUpliftRequest] {
        gatekeeperService.rejectUplift(id, _).map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badStateResponse
      } recover recovery
  }

  def resendVerification(id: ApplicationId) = requiresAuthentication().async(parse.json) {
    implicit request =>
      withJsonBody[ResendVerificationRequest] { resendVerificationPayload =>
        gatekeeperService.resendVerification(id, resendVerificationPayload.gatekeeperUserId).map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badResendResponse
      } recover recovery
  }

  def blockApplication(id: ApplicationId) = requiresAuthentication().async { _ =>
    gatekeeperService.blockApplication(id) map {
      case Blocked => Ok
    } recover recovery
  }

  def unblockApplication(id: ApplicationId) = requiresAuthentication().async { _ =>
    gatekeeperService.unblockApplication(id) map {
      case Unblocked => Ok
    } recover recovery
  }

  def fetchAppsForGatekeeper = anyAuthenticatedUserAction { loggedInRequest =>
    gatekeeperService.fetchNonTestingAppsWithSubmittedDate() map {
      apps => Ok(Json.toJson(apps))
    } recover recovery
  }

  def fetchAllAppsWithSubscriptions(): Action[AnyContent] = anyAuthenticatedUserAction {
    _ => gatekeeperService.fetchAllWithSubscriptions() map { apps => Ok(Json.toJson(apps)) } recover recovery
  }

  def fetchAppById(id: ApplicationId) = anyAuthenticatedUserAction { loggedInRequest =>
    gatekeeperService.fetchAppWithHistory(id) map (app => Ok(Json.toJson(app))) recover recovery
  }

  def fetchAppStateHistoryById(id: ApplicationId) = anyAuthenticatedUserAction { loggedInRequest =>
    gatekeeperService.fetchAppStateHistoryById(id) map (app => Ok(Json.toJson(app))) recover recovery
  }

  def fetchAppStateHistories() = anyAuthenticatedUserAction { _ =>
    gatekeeperService.fetchAppStateHistories() map (histories => Ok(Json.toJson(histories))) recover recovery
  }

  def fetchAllForCollaborator(userId: UserId) = Action.async {
    applicationService.fetchAllForCollaborator(userId, true).map(apps => Ok(Json.toJson(apps))) recover recovery
  }

  def deleteApplication(id: ApplicationId) =
    requiresAuthentication().async { implicit request =>
      withJsonBodyFromAnyContent[DeleteApplicationRequest] { deleteApplicationPayload =>
        gatekeeperService.deleteApplication(id, deleteApplicationPayload)
          .map(_ => NoContent)
          .recover(recovery)
      }
    }

  def createInvitation(
      applicationId: ApplicationId
    ) = anyAuthenticatedGatekeeperUserWithProductionApplicationAndNoSubmissionAndNoInvitation()(applicationId) { implicit applicationRequest =>
    termsOfUseInvitationService
      .createInvitation(applicationRequest.application)
      .map {
        case Some(invite) => Created
        case _            => InternalServerError
      }.recover(recovery)
  }
}
