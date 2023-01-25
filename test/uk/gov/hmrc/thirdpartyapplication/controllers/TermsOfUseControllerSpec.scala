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

import scala.concurrent.ExecutionContext.Implicits.global

import play.api.test.FakeRequest
import play.api.test.Helpers.{contentAsJson, status, stubControllerComponents}
import play.api.http.Status._
import uk.gov.hmrc.thirdpartyapplication.mocks.services.TermsOfUseServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse
import java.time.Instant
import java.time.temporal.ChronoUnit._
import play.api.libs.json.Json

class TermsOfUseControllerSpec extends ControllerSpec {
  trait Setup extends TermsOfUseServiceMockModule {
    val applicationId = ApplicationId.random

    lazy val underTest = new TermsOfUseController(
      TermsOfUseServiceMock.aMock,
      stubControllerComponents()
    )
  }

  "create invitation" should {
    "return CREATED when a terms of use invitation is created" in new Setup {
      TermsOfUseServiceMock.CreateInvitations.thenReturnSuccess()

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe CREATED
    }

    "return BAD_REQUEST when a terms of use invitation is NOT created" in new Setup {
      TermsOfUseServiceMock.CreateInvitations.thenFail()

      val result = underTest.createInvitation(applicationId)(FakeRequest.apply())

      status(result) shouldBe BAD_REQUEST
    }
  }

  "fetch invitations" should {
    "return terms of use invitations" in new Setup {
      val invitations = List(
        TermsOfUseInvitationResponse(
          applicationId,
          Instant.now().truncatedTo(MILLIS)
        )
      )

      TermsOfUseServiceMock.FetchInvitations.thenReturn(invitations)

      val result = underTest.fetchInvitations()(FakeRequest.apply())

      status(result) shouldBe OK

      val responses = Json.fromJson[List[TermsOfUseInvitationResponse]](contentAsJson(result)).get
      responses.size shouldBe 1
      responses.head.applicationId shouldBe applicationId
    }
  }
}
