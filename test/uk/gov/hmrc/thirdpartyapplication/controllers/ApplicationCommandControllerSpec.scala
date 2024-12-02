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
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, Collaborators, GrantLength}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, DispatchRequest}
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationCommandServiceMockModule, ApplicationServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationCommandControllerSpec
    extends ControllerSpec
    with ControllerTestData
    with TableDrivenPropertyChecks
    with StoredApplicationFixtures
    with ActorTestData
    with ApplicationWithCollaboratorsFixtures
    with CommonApplicationId
    with FixedClock {

  import play.api.test.Helpers._

  implicit val hc: HeaderCarrier = HeaderCarrier()

  trait Setup
      extends ApplicationCommandServiceMockModule with ApplicationServiceMockModule {

    implicit lazy val request: FakeRequest[AnyContentAsEmpty.type] =
      FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    lazy val underTest = new ApplicationCommandController(
      ApplicationCommandServiceMock.aMock,
      ApplicationServiceMock.aMock,
      Helpers.stubControllerComponents()
    )
  }

  val actor                   = Actors.AppCollaborator("fred@smith.com".toLaxEmail)
  val cmd: ApplicationCommand = AddCollaborator(actor, Collaborators.Administrator(UserId.random, "bob@smith.com".toLaxEmail), instant)
  val dispatch                = DispatchRequest(cmd, Set("fred".toLaxEmail))

  implicit val tempWriter: OWrites[DispatchRequest] = Json.writes[DispatchRequest]

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
        s"""{"command":{"actor":{"actorType":"UNKNOWN"},"collaborator":{"userId":"${developerCollaborator.userId.value}","emailAddress":"${developerCollaborator.emailAddress}","role":"DEVELOPER"},"timestamp":"$nowAsText","updateType":"removeCollaborator"},"verifiedCollaboratorsToNotify":["${adminOne.emailAddress}"]}"""
      val cmd      = RemoveCollaborator(Actors.Unknown, developerCollaborator, instant)
      val req      = DispatchRequest(cmd, Set(adminOne.emailAddress))
      import cats.syntax.option._

      "write to json" in {
        Json.toJson(req).toString() shouldBe jsonText
      }
      "read from json" in {
        Json.parse(jsonText).asOpt[DispatchRequest] shouldBe req.some
      }
    }
    "calling update" should {

      "return success if application command request is valid" in new Setup {
        ApplicationCommandServiceMock.AuthenticateAndDispatch.thenReturnSuccess(storedApp)

        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe OK
      }

      "return 422 error if application command request is missing updateType" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "updateType"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing instigator" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "instigator"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing timestamp" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "timestamp"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing gatekeeperUser" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "gatekeeperUser"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing newName" in new Setup {
        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody - "newName"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 400 error if application command request is valid but update fails" in new Setup {
        ApplicationCommandServiceMock.AuthenticateAndDispatch.thenReturnFailed("update failed!")

        val result = underTest.update(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe BAD_REQUEST
      }

    }

    "calling dispatch" should {

      "return success if application command request is valid" in new Setup {
        ApplicationCommandServiceMock.AuthenticateAndDispatch.thenReturnCommandSuccess(storedApp)

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe OK
      }

      "return success if dispatch request is valid" in new Setup {
        ApplicationCommandServiceMock.AuthenticateAndDispatch.thenReturnCommandSuccess(storedApp)

        val result = underTest.dispatch(applicationId)(request.withBody(Json.toJson(dispatch)))

        status(result) shouldBe OK
      }

      "return 422 error if application command request is missing updateType" in new Setup {

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "updateType"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing instigator" in new Setup {

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "instigator"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing timestamp" in new Setup {

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "timestamp"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing gatekeeperUser" in new Setup {

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "gatekeeperUser"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 422 error if application command request is missing newName" in new Setup {
        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody - "newName"))

        ApplicationCommandServiceMock.AuthenticateAndDispatch.verifyNeverCalled
        status(result) shouldBe UNPROCESSABLE_ENTITY
      }

      "return 400 error if application command request is valid but update fails" in new Setup {
        ApplicationCommandServiceMock.AuthenticateAndDispatch.thenReturnFailed("update failed!")

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe BAD_REQUEST
      }

      "return 401 error if application command request is not authorised" in new Setup {
        ApplicationCommandServiceMock.AuthenticateAndDispatch.thenReturnAuthFailed("update failed!")

        val result = underTest.dispatch(applicationId)(request.withBody(validUpdateNameRequestBody))

        status(result) shouldBe UNAUTHORIZED
      }
    }
  }

  "updateGrantLength" when {
    "dispatch request" should {
      val cmd = ChangeGrantLength(gatekeeperUser = "a a", timestamp = instant, grantLength = GrantLength.SIX_MONTHS)
      val req = DispatchRequest(cmd, Set.empty[LaxEmailAddress])
      import cats.syntax.option._

      "read from json where grant length is an Int" in {
        val jsonText =
          """{"command":{"gatekeeperUser":"a a","timestamp":"2020-01-02T03:04:05.006Z","grantLength":180,"updateType":"changeGrantLength"},"verifiedCollaboratorsToNotify":[]}"""
        Json.parse(jsonText).asOpt[DispatchRequest] shouldBe req.some
      }
      "read from json where grant length is a Period" in {
        val jsonText =
          """{"command":{"gatekeeperUser":"a a","timestamp":"2020-01-02T03:04:05.006Z","grantLength":"P180D","updateType":"changeGrantLength"},"verifiedCollaboratorsToNotify":[]}"""
        Json.parse(jsonText).asOpt[DispatchRequest] shouldBe req.some
      }
      "write to json" in {
        val jsonText =
          """{"command":{"gatekeeperUser":"a a","timestamp":"2020-01-02T03:04:05.006Z","grantLength":"P180D","updateType":"changeGrantLength"},"verifiedCollaboratorsToNotify":[]}"""
        Json.toJson(req).toString() shouldBe jsonText
      }
    }
  }
}
