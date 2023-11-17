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
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, UserId}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborators
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommand
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationCommandDispatcherMockModule, ApplicationServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

class ApplicationCommandControllerSpec
    extends ControllerSpec
    with ApplicationStateUtil
    with ControllerTestData
    with TableDrivenPropertyChecks
    with ApplicationTestData {

  import play.api.test.Helpers._

  implicit val hc: HeaderCarrier = HeaderCarrier()

  trait Setup
      extends ApplicationCommandDispatcherMockModule with ApplicationServiceMockModule {

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    lazy val underTest = new ApplicationCommandController(
      ApplicationCommandDispatcherMock.aMock,
      ApplicationServiceMock.aMock,
      Helpers.stubControllerComponents()
    )

    val applicationId = ApplicationId.random
  }

  val actor                   = Actors.AppCollaborator("fred@smith.com".toLaxEmail)
  val cmd: ApplicationCommand = AddCollaborator(actor, Collaborators.Administrator(UserId.random, "bob@smith.com".toLaxEmail), LocalDateTime.now)
  val dispatch                = ApplicationCommandController.DispatchRequest(cmd, Set("fred".toLaxEmail))

  implicit val tempWriter = Json.writes[ApplicationCommandController.DispatchRequest]

  val instigatorUserId = UUID.randomUUID().toString

  "updateName" when {
    val validUpdateNameRequestBody = Json.obj(
      "updateType"     -> "changeProductionApplicationName",
      "instigator"     -> instigatorUserId,
      "timestamp"      -> now,
      "gatekeeperUser" -> gatekeeperUser,
      "newName"        -> "bob"
    )

    "dispatch request" should {
      val jsonText  =
        s"""{"command":{"actor":{"actorType":"UNKNOWN"},"collaborator":{"userId":"${developerCollaborator.userId.value}","emailAddress":"dev@example.com","role":"DEVELOPER"},"timestamp":"2020-01-01T12:00:00Z","updateType":"removeCollaborator"},"verifiedCollaboratorsToNotify":["admin@example.com"]}"""
      val timestamp = LocalDateTime.of(2020, 1, 1, 12, 0, 0)
      val cmd       = RemoveCollaborator(Actors.Unknown, developerCollaborator, timestamp)
      val req       = ApplicationCommandController.DispatchRequest(cmd, Set(anAdminEmail))
      import cats.syntax.option._

      "write to json" in {
        Json.toJson(req).toString() shouldBe jsonText
      }
      "read from json" in {
        Json.parse(jsonText).asOpt[ApplicationCommandController.DispatchRequest] shouldBe req.some
      }
    }
    "calling update" should {

      "return success if application command request is valid" in new Setup {
        ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccess(anApplicationData(applicationId))

        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe OK
      }

      "return 422 error if application command request is missing updateType" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "updateType"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing instigator" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "instigator"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing timestamp" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "timestamp"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing gatekeeperUser" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "gatekeeperUser"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing newName" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "newName"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 400 error if application command request is valid but update fails" in new Setup {
        ApplicationCommandDispatcherMock.Dispatch.thenReturnFailed("update failed!")

        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe BAD_REQUEST
      }

    }

    "calling dispatch" should {

      "return success if application command request is valid" in new Setup {
        ApplicationCommandDispatcherMock.Dispatch.thenReturnCommandSuccess(anApplicationData(applicationId))

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe OK
      }

      "return success if dispatch request is valid" in new Setup {
        ApplicationCommandDispatcherMock.Dispatch.thenReturnCommandSuccess(anApplicationData(applicationId))

        val result = underTest.dispatch(applicationId)(request.withBody(Json.toJson(dispatch)))

        status(result) shouldBe OK
      }

      "return 422 error if application command request is missing updateType" in new Setup {
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "updateType"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing instigator" in new Setup {
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "instigator"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing timestamp" in new Setup {
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "timestamp"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing gatekeeperUser" in new Setup {
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "gatekeeperUser"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing newName" in new Setup {
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "newName"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 400 error if application command request is valid but update fails" in new Setup {
        ApplicationCommandDispatcherMock.Dispatch.thenReturnFailed("update failed!")

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe BAD_REQUEST
      }

    }
  }

}
