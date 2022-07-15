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


import play.api.mvc.Request
import uk.gov.hmrc.apiplatform.modules.gkauth.services._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

trait AnyGatekeeperRoleAuthorisationAction {
  self: BackendController =>
    
  implicit val ec: ExecutionContext
  def ldapGatekeeperRoleAuthorisationService: LdapGatekeeperRoleAuthorisationService
  def strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService
   
  def anyAuthenticatedUserAction(block: Request[_] => Future[Result]): Action[AnyContent] =  {
    Action.async { implicit request => 
      ldapGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole(request)
      .recover { case _ => Left(request) }
      .flatMap(_ match {
        case Left(_) => strideGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole(request).flatMap(_ => block(request))
        case Right(r) => block(request)
      })
    }
  }
}
