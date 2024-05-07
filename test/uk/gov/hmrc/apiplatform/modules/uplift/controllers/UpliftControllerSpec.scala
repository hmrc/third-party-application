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

package uk.gov.hmrc.apiplatform.modules.uplift.controllers

import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer
import org.scalatest.prop.TableDrivenPropertyChecks

import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.controllers.{ControllerSpec, ControllerTestData}
import uk.gov.hmrc.thirdpartyapplication.mocks.UpliftServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class UpliftControllerSpec
    extends ControllerSpec
    with ApplicationStateUtil
    with TableDrivenPropertyChecks
    with UpliftServiceMockModule
    with ControllerTestData
    with FixedClock {

  import play.api.test.Helpers._
  import play.api.test.Helpers

  implicit lazy val materializer: Materializer = NoMaterializer

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    def canDeleteApplications() = true
    def enabled()               = true

    val underTest = new UpliftController(
      UpliftServiceMock.aMock,
      Helpers.stubControllerComponents()
    )
  }

  "verifyUplift" should {

    "verify uplift successfully" in new Setup {
      val verificationCode = "aVerificationCode"

      UpliftServiceMock.VerifyUplift.thenSucceeds()

      val result = underTest.verifyUplift(verificationCode)(request)
      status(result) shouldBe NO_CONTENT
    }

    "verify uplift failed" in new Setup {
      val verificationCode = "aVerificationCode"

      UpliftServiceMock.VerifyUplift.thenFailWithInvalidUpliftVerificationCode()

      val result = underTest.verifyUplift(verificationCode)(request)
      status(result) shouldBe BAD_REQUEST
    }
  }

}
