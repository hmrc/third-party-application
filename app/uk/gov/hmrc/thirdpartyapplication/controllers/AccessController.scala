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
import play.api.libs.json.Json.toJson
import play.api.mvc.ControllerComponents
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services.{AccessService, ApplicationService}

import scala.concurrent.ExecutionContext
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.controllers.actions.ApplicationTypeAuthorisationActions
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationService

@Singleton
class AccessController @Inject() (
    val strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService,
    val applicationService: ApplicationService,
    accessService: AccessService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) with JsonUtils with ApplicationTypeAuthorisationActions {

  def readScopes(applicationId: ApplicationId) = requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async { _ =>
    accessService.readScopes(applicationId) map { scopeResponse =>
      Ok(toJson(scopeResponse))
    } recover recovery
  }

  def updateScopes(applicationId: ApplicationId) = requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async(parse.json) { implicit request =>
    withJsonBody[ScopeRequest] { scopeRequest =>
      accessService.updateScopes(applicationId, scopeRequest) map { scopeResponse =>
        Ok(toJson(scopeResponse))
      } recover recovery
    }
  }

  def readOverrides(applicationId: ApplicationId) = requiresAuthenticationForStandardApplications(applicationId).async { _ =>
    accessService.readOverrides(applicationId) map { overrideResponse =>
      Ok(toJson(overrideResponse))
    } recover recovery
  }

  def updateOverrides(applicationId: ApplicationId) = requiresAuthenticationForStandardApplications(applicationId).async(parse.json) { implicit request =>
    withJsonBody[OverridesRequest] { overridesRequest =>
      accessService.updateOverrides(applicationId, overridesRequest) map { overridesResponse =>
        Ok(toJson(overridesResponse))
      } recover recovery
    }
  }
}
