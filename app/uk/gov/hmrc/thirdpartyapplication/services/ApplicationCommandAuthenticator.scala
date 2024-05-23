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
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.retrieve.EmptyRetrieval
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, GatekeeperMixin}
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig

@Singleton
class ApplicationCommandAuthenticator @Inject() (
    authControlConfig: AuthControlConfig,
    strideAuthRoles: StrideAuthRoles,
    strideAuthConnector: StrideAuthConnector
  )(implicit ec: ExecutionContext
  ) {
  private lazy val hasAnyGatekeeperEnrolment = Enrolment(strideAuthRoles.userRole) or Enrolment(strideAuthRoles.superUserRole) or Enrolment(strideAuthRoles.adminRole)

  def authenticateCommand(cmd: ApplicationCommand)(implicit hc: HeaderCarrier): Future[Boolean] = {
    if (requiresStrideAuthentication(cmd)) {
      isStrideAuthorised()
    } else {
      successful(true)
    }
  }

  private def requiresStrideAuthentication(cmd: ApplicationCommand): Boolean = {
    cmd match {
      case _: GatekeeperMixin => true
      case _                  => false
    }
  }

  private def isStrideAuthorised()(implicit hc: HeaderCarrier): Future[Boolean] = {
    strideAuthConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval)
      .map(_ => {
        println("XXXXX Auth passed XXXXX")
        true
      })
      .recoverWith {
        case NonFatal(_) => {
          println("XXXXX Auth failed XXXXX")
          successful(false)
        }
      }
  }

}
