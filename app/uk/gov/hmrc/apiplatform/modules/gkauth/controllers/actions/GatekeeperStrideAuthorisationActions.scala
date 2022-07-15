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

package uk.gov.hmrc.apiplatform.modules.gkauth.controllers.actions

import play.api.mvc.{Action, AnyContent, Result}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperStrideRole
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperRoles
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.LoggedInRequest

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.ActionRefiner
import play.api.mvc.Request
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperRole
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperRoles.READ_ONLY
import uk.gov.hmrc.apiplatform.modules.gkauth.services._
import uk.gov.hmrc.thirdpartyapplication.controllers.StrideGatekeeperAuthorise

trait AnyStrideUserActionMixin {
  self: BackendController with StrideGatekeeperAuthorise =>

  def strideAuthorisationService: StrideAuthorisationService

  implicit def ec: ExecutionContext

  private def gatekeeperRoleActionRefiner(minimumRoleRequired: GatekeeperRole): ActionRefiner[Request, LoggedInRequest] = 
    new ActionRefiner[Request, LoggedInRequest] {
      def executionContext = ec

      def refine[A](request: Request[A]): Future[Either[Result, LoggedInRequest[A]]] = {
        
        val strideRoleRequired: GatekeeperStrideRole = minimumRoleRequired match {
          case READ_ONLY => GatekeeperRoles.USER  // The lowest stride category
          case gsr: GatekeeperStrideRole => gsr
        }

        strideAuthorisationService.createStrideRefiner(strideRoleRequired)(request)
      }
    }

  private def gatekeeperRoleAction(minimumRoleRequired: GatekeeperRole)(block: LoggedInRequest[_] => Future[Result]): Action[AnyContent] =
    Action.async { implicit request =>
      gatekeeperRoleActionRefiner(minimumRoleRequired).invokeBlock(request, block)
    }

  def requiresGatekeeperRoleAction(block: LoggedInRequest[_] => Future[Result]): Action[AnyContent] =
    gatekeeperRoleAction(GatekeeperRoles.USER)(block)

  def atLeastSuperUserAction(block: LoggedInRequest[_] => Future[Result]): Action[AnyContent] =
    gatekeeperRoleAction(GatekeeperRoles.SUPERUSER)(block)

  def adminOnlyAction(block: LoggedInRequest[_] => Future[Result]): Action[AnyContent] =
    gatekeeperRoleAction(GatekeeperRoles.ADMIN)(block)
}

trait GatekeeperAuthorisationActions {
  self: BackendController with AnyStrideUserActionMixin =>
    
  def ldapAuthorisationService: LdapAuthorisationService
    
  def anyAuthenticatedUserAction(block: LoggedInRequest[_] => Future[Result]): Action[AnyContent] =  {
    Action.async { implicit request => 
      ldapAuthorisationService.refineLdap(request)
      .recover { case _ => Left(request) }
      .flatMap(_ match {
        case Left(_) => requiresGatekeeperRoleAction(block)(request)
        case Right(lir) => block(lir)
      })
    }
  }
}
