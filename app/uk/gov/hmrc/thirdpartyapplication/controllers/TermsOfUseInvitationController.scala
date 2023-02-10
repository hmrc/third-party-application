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

import play.api.libs.json.Json.toJson
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationDataService, TermsOfUseInvitationService}

@Singleton
class TermsOfUseInvitationController @Inject() (
    val termsOfUseInvitationService: TermsOfUseInvitationService,
    val applicationDataService: ApplicationDataService,
    val submissionsService: SubmissionsService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) with JsonUtils {

  def fetchInvitation(applicationId: ApplicationId) = Action.async { _ =>
    termsOfUseInvitationService.fetchInvitation(applicationId).map {
      case Some(response) => Ok(toJson(response))
      case None           => NotFound
    }
  }

  def fetchInvitations() = Action.async { _ =>
    termsOfUseInvitationService.fetchInvitations().map(res => Ok(toJson(res)))
  }
}
