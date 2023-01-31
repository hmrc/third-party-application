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
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json.Json.toJson
import play.api.mvc.{ControllerComponents, Result}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse
import uk.gov.hmrc.thirdpartyapplication.services.TermsOfUseInvitationService

@Singleton
class TermsOfUseInvitationController @Inject() (
    termsOfUseService: TermsOfUseInvitationService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) with JsonUtils {

  def createInvitation(applicationId: ApplicationId) = Action.async { _ =>
    def findExistingInvitation(applicationId: ApplicationId): Future[Option[TermsOfUseInvitationResponse]] = termsOfUseService.fetchInvitation(applicationId)

    def createNewInvitation(applicationId: ApplicationId): Future[Result] = {
      termsOfUseService
        .createInvitation(applicationId)
        .map {
          case true => Created
          case _    => InternalServerError
        }
    }

    findExistingInvitation(applicationId).flatMap {
      case Some(response) => successful(Conflict)
      case None           => createNewInvitation(applicationId)
    }.recover(recovery)
  }

  def fetchInvitation(applicationId: ApplicationId) = Action.async { _ =>
    termsOfUseService.fetchInvitation(applicationId).map {
      case Some(response) => Ok(toJson(response))
      case None           => NotFound
    }
  }

  def fetchInvitations() = Action.async { _ =>
    termsOfUseService.fetchInvitations().map(res => Ok(toJson(res)))
  }
}
