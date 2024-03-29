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

import scala.concurrent.Future
import scala.concurrent.Future.successful

import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.controllers.{ErrorCode, JsErrorResponse}

abstract class AbstractGatekeeperRoleAuthorisationService(authControlConfig: AuthControlConfig) extends ApplicationLogger {

  lazy val UNAUTHORIZED_RESPONSE = successful(Some(Results.Unauthorized(JsErrorResponse(ErrorCode.UNAUTHORIZED, "Unauthorised"))))
  protected lazy val OK_RESPONSE = successful(None)

  def ensureHasGatekeeperRole[A]()(implicit hc: HeaderCarrier): Future[Option[Result]] = {
    if (authControlConfig.enabled) {
      innerEnsureHasGatekeeperRole()
    } else {
      Future.successful(None)
    }
  }

  protected def innerEnsureHasGatekeeperRole[A]()(implicit hc: HeaderCarrier): Future[Option[Result]]
}
