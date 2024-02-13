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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers

import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.ResponsibleIndividualVerificationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class ResponsibleIndividualVerificationControllerSpec extends AsyncHmrcSpec with FixedClock {
  implicit val mat: Materializer = NoMaterializer
  val code                       = "12345678"
  val name                       = "bob example"
  val emailAddress               = "test@example.com"
  val appId                      = ApplicationId.random

  trait Setup
      extends ResponsibleIndividualVerificationServiceMockModule {

    val underTest = new ResponsibleIndividualVerificationController(
      ResponsibleIndividualVerificationServiceMock.aMock,
      Helpers.stubControllerComponents()
    )

  }

  "getVerification" should {
    val request = FakeRequest()

    "return verification record if found" in new Setup {
      ResponsibleIndividualVerificationServiceMock.GetVerification.thenGetVerification(code)

      val result = underTest.getVerification(code)(request)

      status(result) shouldBe OK
    }

    "return not found if not found" in new Setup {
      ResponsibleIndividualVerificationServiceMock.GetVerification.thenReturnNone()

      val result = underTest.getVerification(code)(request)

      status(result) shouldBe NOT_FOUND
    }
  }
}
