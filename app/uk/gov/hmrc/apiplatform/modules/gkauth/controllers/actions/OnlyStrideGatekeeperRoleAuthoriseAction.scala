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

package uk.gov.hmrc.thirdpartyapplication.controllers

import scala.concurrent.{ExecutionContext, Future}

import play.api.mvc.{BaseController, _}
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import uk.gov.hmrc.apiplatform.modules.gkauth.services._

trait OnlyStrideGatekeeperRoleAuthoriseAction {
  self: BaseController =>

  implicit val ec: ExecutionContext

  def strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService

  private def authenticationAction = new ActionFilter[Request] {
    protected def executionContext: ExecutionContext = ec

    def filter[A](input: Request[A]): Future[Option[Result]] = {
      implicit val hc = HeaderCarrierConverter.fromRequest(input)
      strideGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole()
    }
  }

  def requiresAuthentication(): ActionBuilder[Request, AnyContent] = Action andThen authenticationAction
}
