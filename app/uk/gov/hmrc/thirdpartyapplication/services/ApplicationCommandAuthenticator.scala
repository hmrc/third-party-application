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
import uk.gov.hmrc.auth.core.retrieve.Name
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, GatekeeperMixin}
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles

@Singleton
class ApplicationCommandAuthenticator @Inject() (
    strideAuthRoles: StrideAuthRoles,
    strideAuthConnector: StrideAuthConnector
  )(implicit ec: ExecutionContext
  ) {

  def authenticateCommand(cmd: ApplicationCommand)(implicit hc: HeaderCarrier): Future[Boolean] = {
    cmd match {
      case gkcmd: ApplicationCommand with GatekeeperMixin => isStrideAuthorised(gkcmd)
      case _                                              => successful(true)
    }
  }

  private def isStrideAuthorised(gkcmd: ApplicationCommand with GatekeeperMixin)(implicit hc: HeaderCarrier): Future[Boolean] = {
    authorise() map {
      case Some(name) => checkName(gkcmd, name)
      case _          => false
    } recover {
      case NonFatal(_) => false
    }
  }

  private def authorise()(implicit hc: HeaderCarrier): Future[Option[Name]] = {
    val hasAnyGatekeeperEnrolment = Enrolment(strideAuthRoles.userRole) or Enrolment(strideAuthRoles.superUserRole) or Enrolment(strideAuthRoles.adminRole)
    val retrieval                 = Retrievals.name
    strideAuthConnector.authorise(hasAnyGatekeeperEnrolment, retrieval)
  }

  private def checkName(gkcmd: ApplicationCommand with GatekeeperMixin, retrieveName: Name): Boolean = {
    retrieveName.name.fold(false)(name => gkcmd.gatekeeperUser.equalsIgnoreCase(name))
  }
}
