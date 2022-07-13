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

package uk.gov.hmrc.thirdpartyapplication.controllers

import play.api.mvc.BaseController
import scala.concurrent.ExecutionContext
import play.api.mvc._
import scala.concurrent.Future

trait StrideGatekeeperAuthoriseAction {
  self: BaseController with JsonUtils with StrideGatekeeperAuthorise =>

  private def authenticationAction(implicit ec: ExecutionContext) = new ActionFilter[Request] {
    def executionContext = ec

    def filter[A](input: Request[A]): Future[None.type] = authenticate(input)
  }

  def requiresAuthentication(): ActionBuilder[Request, AnyContent] = Action andThen authenticationAction
}
