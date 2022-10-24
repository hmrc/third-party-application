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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ApiSubscribed, CollaboratorActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class SubscribeToApiCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with ApiIdentifierSyntax {

  trait Setup {
    val underTest = new SubscribeToApiCommandHandler()

    val applicationId = ApplicationId.random
    
    val developerEmail = "developer@example.com"
    val developerUserId = idOf(developerEmail)
    val developerCollaborator = Collaborator(developerEmail, Role.DEVELOPER, developerUserId)
    val developerActor = CollaboratorActor(developerEmail)

    val app = anApplicationData(applicationId).copy(collaborators = Set(developerCollaborator))

    val apiIdentifier = "some-context".asIdentifier("1.1")
    val timestamp = LocalDateTime.now

    val subscribeToApi = SubscribeToApi(developerActor, apiIdentifier, timestamp)
  }

  "process" should {
    "create a valid event" in new Setup {
      val result = await(underTest.process(app, subscribeToApi))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ApiSubscribed]
      event.applicationId shouldBe applicationId
      event.actor shouldBe developerActor
      event.eventDateTime shouldBe timestamp
      event.context shouldBe apiIdentifier.context.value
      event.version shouldBe apiIdentifier.version.value
    }

    "sad path - fails authentication of developer" in new Setup {
      // TODO 5522 ROPC or Privileged App
    }
  }

  /*
  trait PrivilegedAndRopcSetup extends Setup {

    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = true, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: ApplicationId, testBlock: => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised

      testWithPrivilegedAndRopc(applicationId, gatekeeperLoggedIn = false, testBlock)
    }

    private def testWithPrivilegedAndRopc(applicationId: ApplicationId, gatekeeperLoggedIn: Boolean, testBlock: => Unit): Unit = {
      when(underTest.applicationService.fetch(applicationId))
        .thenReturn(
          OptionT.pure[Future](aNewApplicationResponse(privilegedAccess)),
          OptionT.pure[Future](aNewApplicationResponse(ropcAccess))
        )
      testBlock
      testBlock
    }
  }

  "createSubscriptionForApi, an authenticated request" should {

    val subscribeToApi = SubscribeToApi(CollaboratorActor("dev@example.com"), ApiIdentifier.random, LocalDateTime.now())
    val subscribeToApiRequestBody = Json.toJsObject(subscribeToApi) ++ Json.obj("updateType" -> "subscribeToApi")

    "fail with a 404 (not found) when no application exists for the given application id" in new Setup {
      ApplicationServiceMock.Fetch.thenReturnNothingFor(applicationId)

      val result = underTest.update(applicationId)(request.withBody(subscribeToApiRequestBody))

      verifyErrorResult(result, NOT_FOUND, ErrorCode.APPLICATION_NOT_FOUND)
    }

    "succeed (OK) when a subscription is successfully added to a STANDARD application" in new Setup {
      ApplicationServiceMock.Fetch.thenReturnFor(applicationId)(aNewApplicationResponse())
      ApplicationUpdateServiceMock.Update.thenReturnSuccess(anApplicationData(applicationId))

      val result = underTest.update(applicationId)(request.withBody(subscribeToApiRequestBody))

      status(result) shouldBe OK
    }

    "succeed (Ok) when a subscription is successfully added to a PRIVILEGED or ROPC application and the gatekeeper is logged in" in
      new PrivilegedAndRopcSetup {

        StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

        testWithPrivilegedAndRopcGatekeeperLoggedIn(
          applicationId, {
            ApplicationUpdateServiceMock.Update.thenReturnSuccess(anApplicationData(applicationId))

            val result = underTest.update(applicationId)(request.withBody(subscribeToApiRequestBody))

            status(result) shouldBe OK
          }
        )
      }

    "fail with 401 (Unauthorized) when adding a subscription to a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in
      new PrivilegedAndRopcSetup {

        testWithPrivilegedAndRopcGatekeeperNotLoggedIn(
          applicationId, {
            val result = underTest.update(applicationId)(request.withBody(subscribeToApiRequestBody))

            status(result) shouldBe UNAUTHORIZED
          }
        )
      }
  }
*/
}