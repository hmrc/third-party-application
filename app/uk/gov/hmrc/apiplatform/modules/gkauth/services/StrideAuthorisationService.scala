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

package uk.gov.hmrc.apiplatform.modules.gkauth.services

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.auth.core.retrieve.{ ~ }

import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperStrideRole
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperRoles
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.LoggedInRequest
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperRole
import uk.gov.hmrc.apiplatform.modules.gkauth.controllers.actions.ForbiddenHandler

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.Request
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import play.api.mvc.Result
import uk.gov.hmrc.auth.core.retrieve.Name
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector

@Singleton
class StrideAuthorisationService @Inject() (
  authConnector: AuthConnector,
  forbiddenHandler: ForbiddenHandler,
  strideAuthConfig: StrideAuthConnector.Config
)(implicit val ec: ExecutionContext) {

  import strideAuthConfig.roles._

  def createStrideRefiner[A](strideRoleRequired: GatekeeperStrideRole): (Request[A]) => Future[Either[Result, LoggedInRequest[A]]] = implicit request => {
    implicit val hc = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

    authorise(strideRoleRequired) map {
      case Some(name) ~ authorisedEnrolments => 
        def applyRole(role: GatekeeperRole): Either[Result, LoggedInRequest[A]] = {
          Right(new LoggedInRequest(name.name, role, request))
        }

        ( authorisedEnrolments.getEnrolment(adminRole).isDefined, authorisedEnrolments.getEnrolment(superUserRole).isDefined, authorisedEnrolments.getEnrolment(userRole).isDefined ) match {
          case (true, _, _) => applyRole(GatekeeperRoles.ADMIN)
          case (_, true, _) => applyRole(GatekeeperRoles.SUPERUSER)
          case (_, _, true) => applyRole(GatekeeperRoles.USER)
          case _            => Left(forbiddenHandler.handle(request))
        }

      case None ~ authorisedEnrolments       => Left(forbiddenHandler.handle(request))
    } recover {
      case _: NoActiveSession                => Left(forbiddenHandler.handle(request))
      case _: InsufficientEnrolments         => Left(forbiddenHandler.handle(request))
    }
  }

  private def authorise(strideRoleRequired: GatekeeperStrideRole)(implicit hc: HeaderCarrier): Future[~[Option[Name], Enrolments]] = {
    val predicate = StrideAuthorisationPredicateForGatekeeperRole(strideAuthConfig.roles)(strideRoleRequired)
    val retrieval = Retrievals.name and Retrievals.authorisedEnrolments
    
    authConnector.authorise(predicate, retrieval)
  }
}
