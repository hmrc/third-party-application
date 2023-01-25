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
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.services.TermsOfUseService
import play.api.libs.json.Json.toJson

@Singleton
class TermsOfUseController @Inject()(
  termsOfUseService: TermsOfUseService,
  cc: ControllerComponents
)(implicit val ec: ExecutionContext) extends BackendController(cc) with JsonUtils {
  def createInvitation(id: ApplicationId) = Action.async { _ =>
    termsOfUseService
      .createInvitation(id)
      .map {
        case true => Created
        case _ => BadRequest
      }
      .recover(recovery)
  }

  def fetchInvitations() = Action.async { _ =>
    termsOfUseService.fetchInvitations().map(res => Ok(toJson(res)))
  }
}
