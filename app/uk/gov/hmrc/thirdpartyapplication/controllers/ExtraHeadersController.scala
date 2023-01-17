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

import play.api.mvc.{ControllerComponents, RequestHeader}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

abstract class ExtraHeadersController(cc: ControllerComponents) extends BackendController(cc) {

  // This header is not expected to reach outside but is used to pass information further down the call stack.
  // TODO - tidy this up to use a better way to decorate calls with the knowledge they came from API Gateway (or not)
  val INTERNAL_USER_AGENT = "X-GATEWAY-USER-AGENT"

  override implicit def hc(implicit request: RequestHeader): HeaderCarrier = {
    def header(key: String)                        = request.headers.get(key) map (key -> _)
    def renamedHeader(key: String, newKey: String) = request.headers.get(key) map (newKey -> _)

    val extraHeaders =
      List(header(LOGGED_IN_USER_NAME_HEADER), header(LOGGED_IN_USER_EMAIL_HEADER), header(SERVER_TOKEN_HEADER), renamedHeader(USER_AGENT, INTERNAL_USER_AGENT)).flatten
    super.hc.withExtraHeaders(extraHeaders: _*)
  }
}
