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

package uk.gov.hmrc.apiplatform.modules.gkauth.controllers.actions

import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import play.api.mvc.{Action, AnyContent, Request, Result}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.gkauth.services._

trait AnyGatekeeperRoleAuthorisationAction extends ApplicationLogger {
  self: BackendController =>

  implicit val ec: ExecutionContext
  def ldapGatekeeperRoleAuthorisationService: LdapGatekeeperRoleAuthorisationService
  def strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService

  def anyAuthenticatedUserAction(block: Request[_] => Future[Result]): Action[AnyContent] = {
    Action.async { implicit request =>
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
      ldapGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole()
        .recoverWith { case NonFatal(_) =>
          logger.warn("LDAP Authenticate errored trying to find user, trying stride")
          ldapGatekeeperRoleAuthorisationService.UNAUTHORIZED_RESPONSE
        }
        .flatMap(_ match {
          case Some(failureToAuthorise) =>
            strideGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole()
              .flatMap(_ match {
                case None                     => block(request)
                case Some(failureToAuthorise) => successful(failureToAuthorise)
              })
          case None                     => block(request)
        })
    }
  }
}
