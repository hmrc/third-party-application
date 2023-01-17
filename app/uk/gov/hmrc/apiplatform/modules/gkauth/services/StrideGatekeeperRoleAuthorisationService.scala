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

package uk.gov.hmrc.apiplatform.modules.gkauth.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import play.api.mvc._
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.retrieve.EmptyRetrieval
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig

@Singleton
class StrideGatekeeperRoleAuthorisationService @Inject() (
    authControlConfig: AuthControlConfig,
    strideAuthRoles: StrideAuthRoles,
    strideAuthConnector: StrideAuthConnector
  )(implicit val ec: ExecutionContext
  ) extends AbstractGatekeeperRoleAuthorisationService(authControlConfig) {

  private lazy val hasAnyGatekeeperEnrolment = Enrolment(strideAuthRoles.userRole) or Enrolment(strideAuthRoles.superUserRole) or Enrolment(strideAuthRoles.adminRole)

  protected def innerEnsureHasGatekeeperRole[A]()(implicit hc: HeaderCarrier): Future[Option[Result]] = {
    strideAuthConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval)
      .map(_ => None)
      .recoverWith {
        case NonFatal(_) => UNAUTHORIZED_RESPONSE
      }
  }
}
