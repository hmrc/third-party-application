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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers

import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.test.Helpers._
import play.api.test.Helpers
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.ResponsibleIndividualVerificationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import play.api.test.FakeRequest
import akka.stream.testkit.NoMaterializer
import play.api.libs.json.Json

class ResponsibleIndividualVerificationControllerSpec extends AsyncHmrcSpec with FixedClock {
  import uk.gov.hmrc.apiplatform.modules.approvals.domain.services.ResponsibleIndividualVerificationFrontendJsonFormatters._
  implicit val mat = NoMaterializer
  val code         = "12345678"
  val name         = "bob example"
  val emailAddress = "test@example.com"
  val appId        = ApplicationId.random

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

  "accept" should {
    implicit val writes = Json.writes[ResponsibleIndividualVerificationController.ResponsibleIndividualVerificationRequest]
    val jsonBody        = Json.toJson(ResponsibleIndividualVerificationController.ResponsibleIndividualVerificationRequest(code))
    val request         = FakeRequest().withJsonBody(jsonBody)

    "return ok if accepted successfully" in new Setup {
      ResponsibleIndividualVerificationServiceMock.Accept.thenAccept()

      val result = underTest.accept()(request)

      status(result) shouldBe OK
    }
  }

  "decline" should {
    implicit val writes = Json.writes[ResponsibleIndividualVerificationController.ResponsibleIndividualVerificationRequest]
    val jsonBody        = Json.toJson(ResponsibleIndividualVerificationController.ResponsibleIndividualVerificationRequest(code))
    val request         = FakeRequest().withJsonBody(jsonBody)

    "return ok if declined successfully" in new Setup {
      ResponsibleIndividualVerificationServiceMock.Decline.thenDecline()

      val result = underTest.decline()(request)

      status(result) shouldBe OK
    }
  }
}
