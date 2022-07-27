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

package uk.gov.hmrc.thirdpartyapplication.controllers

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, GatekeeperService}

import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future.successful
import scala.util.{Try, Success, Failure}
import uk.gov.hmrc.apiplatform.modules.gkauth.controllers.actions._
import uk.gov.hmrc.apiplatform.modules.gkauth.services.LdapGatekeeperRoleAuthorisationService
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationService

@Singleton
class GatekeeperController @Inject() (
    val applicationService: ApplicationService,
    val ldapGatekeeperRoleAuthorisationService: LdapGatekeeperRoleAuthorisationService,
    val strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService,
    gatekeeperService: GatekeeperService,
    subscriptionService: SubscriptionService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc)
  with JsonUtils
  with AnyGatekeeperRoleAuthorisationAction
  with OnlyStrideGatekeeperRoleAuthoriseAction {

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

  def fetchAppById(id: ApplicationId) = anyAuthenticatedUserAction { loggedInRequest =>
    gatekeeperService.fetchAppWithHistory(id) map (app => Ok(Json.toJson(app))) recover recovery
  }

  def fetchAppStateHistoryById(id: ApplicationId) = anyAuthenticatedUserAction { loggedInRequest =>
    gatekeeperService.fetchAppStateHistoryById(id) map (app => Ok(Json.toJson(app))) recover recovery
  }

  def fetchAppStateHistories() = anyAuthenticatedUserAction { _ =>
    gatekeeperService.fetchAppStateHistories() map (histories => Ok(Json.toJson(histories))) recover recovery
  }

  // TODO - this should use a request with payload validation in the JSformatter
  def updateRateLimitTier(applicationId: ApplicationId) = requiresAuthentication().async(parse.json) { implicit request =>
    withJsonBody[UpdateRateLimitTierRequest] { updateRateLimitTierRequest =>
      Try(RateLimitTier withName updateRateLimitTierRequest.rateLimitTier.toUpperCase()) match {
        case Success(rateLimitTier) =>
          applicationService.updateRateLimitTier(applicationId, rateLimitTier) map(_ => NoContent) recover recovery
        case Failure(_)                        => 
          successful(UnprocessableEntity(
            JsErrorResponse(INVALID_REQUEST_PAYLOAD, s"'${updateRateLimitTierRequest.rateLimitTier}' is an invalid rate limit tier")
          ))
      }
    }
    .recover(recovery)
  }
 
  def deleteApplication(id: ApplicationId) =
    requiresAuthentication().async { implicit request =>
      withJsonBodyFromAnyContent[DeleteApplicationRequest] { deleteApplicationPayload =>
        gatekeeperService.deleteApplication(id, deleteApplicationPayload)
          .map(_ => NoContent)
          .recover(recovery)
      }
    }
}
