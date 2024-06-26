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

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.prop.TableDrivenPropertyChecks

import play.api.libs.json.{Json, OWrites}
import play.api.mvc._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{Collaborators, GrantLength}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommand
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationCommandAuthenticatorMockModule, ApplicationCommandDispatcherMockModule, ApplicationServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

class ApplicationCommandControllerSpec
    extends ControllerSpec
    with ApplicationStateUtil
    with ControllerTestData
    with TableDrivenPropertyChecks
    with ApplicationTestData
    with FixedClock {

  import play.api.test.Helpers._

  implicit val hc: HeaderCarrier = HeaderCarrier()

  trait Setup
      extends ApplicationCommandDispatcherMockModule with ApplicationServiceMockModule with ApplicationCommandAuthenticatorMockModule {

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    lazy val underTest = new ApplicationCommandController(
      ApplicationCommandDispatcherMock.aMock,
      ApplicationCommandAuthenticatorMock.aMock,
      ApplicationServiceMock.aMock,
      Helpers.stubControllerComponents()
    )

    val applicationId = ApplicationId.random
  }

  val actor                   = Actors.AppCollaborator("fred@smith.com".toLaxEmail)
  val cmd: ApplicationCommand = AddCollaborator(actor, Collaborators.Administrator(UserId.random, "bob@smith.com".toLaxEmail), instant)
  val dispatch                = ApplicationCommandController.DispatchRequest(cmd, Set("fred".toLaxEmail))

  implicit val tempWriter: OWrites[ApplicationCommandController.DispatchRequest] = Json.writes[ApplicationCommandController.DispatchRequest]

  val instigatorUserId = UUID.randomUUID().toString

  "updateName" when {
    val validUpdateNameRequestBody = Json.obj(
      "updateType"     -> "changeProductionApplicationName",
      "instigator"     -> instigatorUserId,
      "timestamp"      -> instant,
      "gatekeeperUser" -> gatekeeperUser,
      "newName"        -> "bob"
    )

    "dispatch request" should {
      val jsonText =
        s"""{"command":{"actor":{"actorType":"UNKNOWN"},"collaborator":{"userId":"${developerCollaborator.userId.value}","emailAddress":"dev@example.com","role":"DEVELOPER"},"timestamp":"$nowAsText","updateType":"removeCollaborator"},"verifiedCollaboratorsToNotify":["admin@example.com"]}"""
      val cmd      = RemoveCollaborator(Actors.Unknown, developerCollaborator, instant)
      val req      = ApplicationCommandController.DispatchRequest(cmd, Set(anAdminEmail))
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
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        ApplicationCommandDispatcherMock.Dispatch.thenReturnCommandSuccess(anApplicationData(applicationId))

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe OK
      }

      "return success if dispatch request is valid" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        ApplicationCommandDispatcherMock.Dispatch.thenReturnCommandSuccess(anApplicationData(applicationId))

        val result = underTest.dispatch(applicationId)(request.withBody(Json.toJson(dispatch)))

        status(result) shouldBe OK
      }

      "return 422 error if application command request is missing updateType" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "updateType"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing instigator" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "instigator"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing timestamp" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "timestamp"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing gatekeeperUser" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "gatekeeperUser"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing newName" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "newName"))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 400 error if application command request is valid but update fails" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.succeeds()
        ApplicationCommandDispatcherMock.Dispatch.thenReturnFailed("update failed!")

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe BAD_REQUEST
      }

      "return 401 error if application command request is not authorised" in new Setup {
        ApplicationCommandAuthenticatorMock.AuthenticateCommand.fails()
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        ApplicationCommandDispatcherMock.Dispatch.verifyNeverCalled
        status(result) shouldBe UNAUTHORIZED
      }
    }
  }

  "updateGrantLength" when {
    "dispatch request" should {
      val cmd = ChangeGrantLength(gatekeeperUser = "a a", timestamp = instant, grantLength = GrantLength.SIX_MONTHS)
      val req = ApplicationCommandController.DispatchRequest(cmd, Set.empty[LaxEmailAddress])
      import cats.syntax.option._

      "read from json where grant length is an Int" in {
        val jsonText =
          """{"command":{"gatekeeperUser":"a a","timestamp":"2020-01-02T03:04:05.006Z","grantLength":180,"updateType":"changeGrantLength"},"verifiedCollaboratorsToNotify":[]}"""
        Json.parse(jsonText).asOpt[ApplicationCommandController.DispatchRequest] shouldBe req.some
      }
      "read from json where grant length is a Period" in {
        val jsonText =
          """{"command":{"gatekeeperUser":"a a","timestamp":"2020-01-02T03:04:05.006Z","grantLength":"P180D","updateType":"changeGrantLength"},"verifiedCollaboratorsToNotify":[]}"""
        Json.parse(jsonText).asOpt[ApplicationCommandController.DispatchRequest] shouldBe req.some
      }
      "write to json" in {
        val jsonText =
          """{"command":{"gatekeeperUser":"a a","timestamp":"2020-01-02T03:04:05.006Z","grantLength":"P180D","updateType":"changeGrantLength"},"verifiedCollaboratorsToNotify":[]}"""
        Json.toJson(req).toString() shouldBe jsonText
      }
    }
  }
}
