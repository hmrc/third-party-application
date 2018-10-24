/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.controllers

import java.util.UUID

import javax.inject.Inject
import play.api.libs.json.Json
import uk.gov.hmrc.connector.AuthConnector
import uk.gov.hmrc.controllers.ErrorCode._
import uk.gov.hmrc.models.{AuthRole, Blocked, InvalidStateTransition}
import uk.gov.hmrc.services.{ApplicationService, GatekeeperService}
import uk.gov.hmrc.models.JsonFormatters._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class GatekeeperController @Inject()(val authConnector: AuthConnector, val applicationService: ApplicationService,
  gatekeeperService: GatekeeperService) extends CommonController with AuthorisationWrapper {

  private lazy val badStateResponse = PreconditionFailed(
    JsErrorResponse(INVALID_STATE_TRANSITION, "Application is not in state 'PENDING_GATEKEEPER_APPROVAL'"))

  private lazy val badResendResponse = PreconditionFailed(
    JsErrorResponse(INVALID_STATE_TRANSITION, "Application is not in state 'PENDING_REQUESTER_VERIFICATION'"))


  def approveUplift(id: UUID) = requiresRole(AuthRole.APIGatekeeper).async(parse.json) {
    implicit request =>
      withJsonBody[ApproveUpliftRequest] { approveUpliftPayload =>
        gatekeeperService.approveUplift(id, approveUpliftPayload.gatekeeperUserId)
          .map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badStateResponse
      } recover recovery
  }

  def rejectUplift(id: UUID) = requiresRole(AuthRole.APIGatekeeper).async(parse.json) {
    implicit request =>
      withJsonBody[RejectUpliftRequest] {
        gatekeeperService.rejectUplift(id, _).map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badStateResponse
      } recover recovery
  }

  def resendVerification(id: UUID) = requiresRole(AuthRole.APIGatekeeper).async(parse.json) {
    implicit request =>
      withJsonBody[ResendVerificationRequest] { resendVerificationPayload =>
        gatekeeperService.resendVerification(id, resendVerificationPayload.gatekeeperUserId).map(_ => NoContent)
      } recover {
        case _: InvalidStateTransition => badResendResponse
      } recover recovery
  }

  def deleteApplication(id: UUID) = requiresRole(AuthRole.APIGatekeeper).async(parse.json) {
    implicit request =>
      withJsonBody[DeleteApplicationRequest] { deleteApplicationPayload =>
        gatekeeperService.deleteApplication(id, deleteApplicationPayload).map(_ => NoContent)
      } recover recovery
  }

  def blockApplication(id: UUID) = requiresRole(AuthRole.APIGatekeeper).async { implicit request =>
    gatekeeperService.blockApplication(id) map {
      case Blocked => Ok
    } recover recovery
  }

  def fetchAppsForGatekeeper = requiresRole(AuthRole.APIGatekeeper).async {
    gatekeeperService.fetchNonTestingAppsWithSubmittedDate() map {
      apps => Ok(Json.toJson(apps))
    } recover recovery
  }

  def fetchAppById(id: UUID) = requiresRole(AuthRole.APIGatekeeper).async {
    gatekeeperService.fetchAppWithHistory(id) map (app => Ok(Json.toJson(app))) recover recovery
  }
}
