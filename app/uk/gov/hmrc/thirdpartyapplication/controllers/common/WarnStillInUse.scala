/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers.common

import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

trait WarnStillInUse {
  self: BackendController with ApplicationLogger =>

  def warnStillInUse[A](method: String)(action: Action[A]) = Action.async(action.parser) { request =>
    logger.warn(s"""Unexpected call to $method from ${request.headers.get("requestid").fold("???")(v => s"RequestId=$v")}${request.headers.get("USER-AGENT").fold("")(v =>
        s" UserAgent=$v"
      )}""")
    action(request)
  }

}
