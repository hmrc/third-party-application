/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.UUID

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.{Blocked, InvalidStateTransition, Unblocked}
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, GatekeeperService}

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class GatekeeperController @Inject()(val authConnector: AuthConnector, val applicationService: ApplicationService,
  gatekeeperService: GatekeeperService, val authConfig: AuthConfig) extends CommonController with AuthorisationWrapper {

  private lazy val badStateResponse = PreconditionFailed(
    JsErrorResponse(INVALID_STATE_TRANSITION, "Application is not in state 'PENDING_GATEKEEPER_APPROVAL'"))

  private lazy val badResendResponse = PreconditionFailed(
    JsErrorResponse(INVALID_STATE_TRANSITION, "Application is not in state 'PENDING_REQUESTER_VERIFICATION'"))


  def approveUplift(id: UUID) = requiresAuthentication().async(parse.json) {
    implicit request =>
      withJsonBody[ApproveUpliftRequest] { approveUpliftPayload =>
        gatekeeperService.approveUplift(id, approveUpliftPayload.gatekeeperUserId)
          .map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badStateResponse
      } recover recovery
  }

  def rejectUplift(id: UUID) = requiresAuthentication().async(parse.json) {
    implicit request =>
      withJsonBody[RejectUpliftRequest] {
        gatekeeperService.rejectUplift(id, _).map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badStateResponse
      } recover recovery
  }

  def resendVerification(id: UUID) = requiresAuthentication().async(parse.json) {
    implicit request =>
      withJsonBody[ResendVerificationRequest] { resendVerificationPayload =>
        gatekeeperService.resendVerification(id, resendVerificationPayload.gatekeeperUserId).map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badResendResponse
      } recover recovery
  }

  def blockApplication(id: UUID) = requiresAuthentication().async { implicit request =>
    gatekeeperService.blockApplication(id) map {
      case Blocked => Ok
    } recover recovery
  }

  def unblockApplication(id: UUID) = requiresAuthentication().async { implicit request =>
    gatekeeperService.unblockApplication(id) map {
      case Unblocked => Ok
    } recover recovery
  }

  def fetchAppsForGatekeeper = requiresAuthentication().async {
    gatekeeperService.fetchNonTestingAppsWithSubmittedDate() map {
      apps => Ok(Json.toJson(apps))
    } recover recovery
  }

  def fetchAppById(id: UUID) = requiresAuthentication().async {
    gatekeeperService.fetchAppWithHistory(id) map (app => Ok(Json.toJson(app))) recover recovery
  }
}
