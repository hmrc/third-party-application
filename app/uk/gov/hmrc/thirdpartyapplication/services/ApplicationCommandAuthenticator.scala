/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, GatekeeperMixin}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.apiplatform.modules.gkauth.services._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import uk.gov.hmrc.auth.core.retrieve.EmptyRetrieval
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.control.NonFatal
import uk.gov.hmrc.auth.core.Enrolment
import scala.concurrent.ExecutionContext
 
@Singleton 
class ApplicationCommandAuthenticator @Inject()(
  strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService,
  authControlConfig: AuthControlConfig,
  strideAuthRoles: StrideAuthRoles,
  strideAuthConnector: StrideAuthConnector
)(implicit ec: ExecutionContext) {
    private lazy val hasAnyGatekeeperEnrolment = Enrolment(strideAuthRoles.userRole) or Enrolment(strideAuthRoles.superUserRole) or Enrolment(strideAuthRoles.adminRole)

    def authenticateCommand(cmd: ApplicationCommand)(implicit hc: HeaderCarrier): Future[Boolean] = {
      if(requiresStrideAuthentication(cmd)){
        for { 
          authResult <- isAuthorised()
          cmdresult =  handleAuthResult(authResult)
        } yield cmdresult
      } else {
        successful(true)
      }
    }

    private def handleAuthResult(isAuthorised: Boolean): Boolean = {
      if(!isAuthorised) {false}
      else {true}
    }

    private def requiresStrideAuthentication(cmd: ApplicationCommand): Boolean = {
      cmd match {
        case _: GatekeeperMixin => true
        case _ => false
      }
    }

    def isAuthorised()(implicit hc: HeaderCarrier): Future[Boolean] = {
      strideAuthConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval)
        .map(_ => true)
        .recoverWith {
          case NonFatal(_) => successful(false)
        }
  }
  
}
