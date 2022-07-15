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

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.Request
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import play.api.mvc.Result
import play.api.mvc.Results._
import uk.gov.hmrc.auth.core.retrieve.Name
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import play.api.mvc.Results.Forbidden
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode
import uk.gov.hmrc.thirdpartyapplication.controllers.JsErrorResponse


@Singleton
class StrideAuthorisationService @Inject() (
  strideAuthConnector: StrideAuthConnector,
  strideAuthRoles: StrideAuthRoles
)(implicit val ec: ExecutionContext) {

  private def handleForbidden(request: Request[_]): Result = Forbidden(JsErrorResponse(ErrorCode.FORBIDDEN, "Forbidden action"))

  def createStrideRefiner[A](strideRoleRequired: GatekeeperStrideRole): (Request[A]) => Future[Either[Result, LoggedInRequest[A]]] = implicit request => {
    implicit val hc = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

    authorise(strideRoleRequired) map {
      case Some(name) ~ authorisedEnrolments => 
        def applyRole(role: GatekeeperRole): Either[Result, LoggedInRequest[A]] = {
          Right(new LoggedInRequest(name.name, role, request))
        }

        ( authorisedEnrolments.getEnrolment(strideAuthRoles.adminRole).isDefined, authorisedEnrolments.getEnrolment(strideAuthRoles.superUserRole).isDefined, authorisedEnrolments.getEnrolment(strideAuthRoles.userRole).isDefined ) match {
          case (true, _, _) => applyRole(GatekeeperRoles.ADMIN)
          case (_, true, _) => applyRole(GatekeeperRoles.SUPERUSER)
          case (_, _, true) => applyRole(GatekeeperRoles.USER)
          case _            => Left(handleForbidden(request))
        }

      case None ~ authorisedEnrolments       => Left(handleForbidden(request))
    } recover {
      case _: NoActiveSession                => Left(handleForbidden(request))
      case _: InsufficientEnrolments         => Left(handleForbidden(request))
    }
  }

  private def authorise(strideRoleRequired: GatekeeperStrideRole)(implicit hc: HeaderCarrier): Future[~[Option[Name], Enrolments]] = {
    val predicate = StrideAuthorisationPredicateForGatekeeperRole(strideAuthRoles)(strideRoleRequired)
    val retrieval = Retrievals.name and Retrievals.authorisedEnrolments
    
    strideAuthConnector.authorise(predicate, retrieval)
  }
}
