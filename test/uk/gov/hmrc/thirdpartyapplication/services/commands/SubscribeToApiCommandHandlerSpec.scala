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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import cats.data.{NonEmptyList, ValidatedNec}
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationServiceMockModule
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ApiSubscribed, GatekeeperUserActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class SubscribeToApiCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with ApiIdentifierSyntax {

  trait Setup extends StrideGatekeeperRoleAuthorisationServiceMockModule with ApplicationServiceMockModule {
    implicit val hc = HeaderCarrier()

    val underTest = new SubscribeToApiCommandHandler(StrideGatekeeperRoleAuthorisationServiceMock.aMock)

    val applicationId       = ApplicationId.random
    val gatekeeperUserActor = GatekeeperUserActor("Gatekeeper Admin")
    val apiIdentifier       = "some-context".asIdentifier("1.1")
    val timestamp           = LocalDateTime.now

    val subscribeToApi = SubscribeToApi(gatekeeperUserActor, apiIdentifier, timestamp)
  }

  trait PrivilegedAndRopcSetup extends Setup {

    def testWithPrivilegedAndRopcGatekeeperLoggedIn(applicationId: ApplicationId, testBlock: ApplicationData => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised
      testWithPrivilegedAndRopc(applicationId, testBlock)
    }

    def testWithPrivilegedAndRopcGatekeeperNotLoggedIn(applicationId: ApplicationId, testBlock: ApplicationData => Unit): Unit = {
      StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.notAuthorised
      testWithPrivilegedAndRopc(applicationId, testBlock)
    }

    private def testWithPrivilegedAndRopc(applicationId: ApplicationId, testBlock: ApplicationData => Unit): Unit = {
      testBlock(anApplicationData(applicationId, access = Privileged(scopes = Set("scope1"))))
      testBlock(anApplicationData(applicationId, access = Ropc()))
    }
  }

  "process" should {

    "create an ApiSubscribed event when adding a subscription to a STANDARD application" in new Setup {
      val app = anApplicationData(applicationId)

      val result = await(underTest.process(app, subscribeToApi))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ApiSubscribed]
      event.applicationId shouldBe applicationId
      event.actor shouldBe gatekeeperUserActor
      event.eventDateTime shouldBe timestamp
      event.context shouldBe apiIdentifier.context.value
      event.version shouldBe apiIdentifier.version.value
    }

    "create an ApiSubscribed event when adding a subscription to a PRIVILEGED or ROPC application and the gatekeeper is logged in" in
      new PrivilegedAndRopcSetup {
        StrideGatekeeperRoleAuthorisationServiceMock.EnsureHasGatekeeperRole.authorised

        testWithPrivilegedAndRopcGatekeeperLoggedIn(
          applicationId,
          { app =>
            val result: ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]] = await(underTest.process(app, subscribeToApi))

            result.isValid shouldBe true
            result.toEither match {
              case Left(_)                                             => fail()
              case Right(events: NonEmptyList[UpdateApplicationEvent]) =>
                val event = events.head.asInstanceOf[ApiSubscribed]
                event.applicationId shouldBe applicationId
                event.actor shouldBe gatekeeperUserActor
                event.eventDateTime shouldBe timestamp
                event.context shouldBe apiIdentifier.context.value
                event.version shouldBe apiIdentifier.version.value
            }
          }
        )
      }

    "return invalid with an error message when adding a subscription to a PRIVILEGED or ROPC application and the gatekeeper is not logged in" in
      new PrivilegedAndRopcSetup {

        testWithPrivilegedAndRopcGatekeeperNotLoggedIn(
          applicationId,
          { app =>
            val result = await(underTest.process(app, subscribeToApi))

            result.isValid shouldBe false
            result.toEither match {
              case Right(_)           => fail()
              case Left(errorMessage) => errorMessage.head shouldBe s"Unauthorized to subscribe any API to app ${app.name}"
            }
          }
        )
      }
  }
}
