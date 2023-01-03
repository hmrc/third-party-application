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

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.LogSuppressing
import scala.concurrent.Future
import play.api.mvc.Result
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._

abstract class ControllerSpec extends AsyncHmrcSpec
    with LogSuppressing {

  import play.api.test.Helpers._

  def verifyErrorResult(result: Future[Result], statusCode: Int, errorCode: ErrorCode): Unit = {
    status(result) shouldBe statusCode
    (contentAsJson(result) \ "code").as[String] shouldBe errorCode.toString
  }
}
