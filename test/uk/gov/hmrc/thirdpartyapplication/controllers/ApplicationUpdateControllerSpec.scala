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

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.prop.TableDrivenPropertyChecks

import play.api.libs.json.Json
import play.api.mvc._
import play.api.test.{FakeRequest, Helpers}

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationServiceMockModule, ApplicationUpdateServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

class ApplicationUpdateControllerSpec
    extends ControllerSpec
    with ApplicationStateUtil
    with ControllerTestData
    with TableDrivenPropertyChecks
    with ApplicationTestData {

  import play.api.test.Helpers._

  trait Setup
      extends ApplicationUpdateServiceMockModule with ApplicationServiceMockModule {

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    lazy val underTest = new ApplicationUpdateController(
      ApplicationUpdateServiceMock.aMock,
      ApplicationServiceMock.aMock,
      Helpers.stubControllerComponents()
    )

    val applicationId = ApplicationId.random
  }

  val instigatorUserId = UUID.randomUUID().toString
  val gatekeeperUser   = "gk_user_1"

  "updateName" should {
    val validUpdateNameRequestBody = Json.obj(
      "updateType"     -> "changeProductionApplicationName",
      "instigator"     -> instigatorUserId,
      "timestamp"      -> LocalDateTime.now(),
      "gatekeeperUser" -> gatekeeperUser,
      "newName"        -> "bob"
    )

    "return success if application update request is valid" in new Setup {
      ApplicationUpdateServiceMock.Update.thenReturnSuccess(anApplicationData(applicationId))

      val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody))

      status(result) shouldBe OK
    }

    "return 422 error if application update request is missing updateType" in new Setup {
      val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "updateType"))

      ApplicationUpdateServiceMock.Update.verifyNeverCalled
      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "return 422 error if application update request is missing instigator" in new Setup {
      val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "instigator"))

      ApplicationUpdateServiceMock.Update.verifyNeverCalled
      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "return 422 error if application update request is missing timestamp" in new Setup {
      val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "timestamp"))

      ApplicationUpdateServiceMock.Update.verifyNeverCalled
      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "return 422 error if application update request is missing gatekeeperUser" in new Setup {
      val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "gatekeeperUser"))

      ApplicationUpdateServiceMock.Update.verifyNeverCalled
      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "return 422 error if application update request is missing newName" in new Setup {
      val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "newName"))

      ApplicationUpdateServiceMock.Update.verifyNeverCalled
      status(result) shouldBe UNPROCESSABLE_ENTITY
    }

    "return 400 error if application update request is valid but update fails" in new Setup {
      ApplicationUpdateServiceMock.Update.thenReturnError("update failed!")

      val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody))

      status(result) shouldBe BAD_REQUEST
    }

  }

}
