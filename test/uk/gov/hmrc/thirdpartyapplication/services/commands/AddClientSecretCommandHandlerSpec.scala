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

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.ClientSecretAddedV2
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.CredentialConfig
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors

class AddClientSecretCommandHandlerSpec
    extends AsyncHmrcSpec
    with ApplicationTestData
    with CommandActorExamples
    with CommandCollaboratorExamples
    with CommandApplicationExamples {

  class Setup(limit: Int = 3) extends ApplicationRepositoryMockModule {
    val config    = CredentialConfig(limit)
    val underTest = new AddClientSecretCommandHandler(ApplicationRepoMock.aMock, config)

    val timestamp    = FixedClock.now
    val secretValue  = "secret"
    val clientSecret = ClientSecret("name", timestamp, hashedSecret = "hashed")

    val addClientSecretByDev   = AddClientSecret(Actors.Collaborator(devEmail), clientSecret, timestamp)
    val addClientSecretByAdmin = AddClientSecret(Actors.Collaborator(adminEmail), clientSecret, timestamp)

    def checkSuccessResult(expectedActor: Actors.Collaborator)(result: CommandHandler.CommandSuccess) = {
      inside(result) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ClientSecretAddedV2(_, appId, eventDateTime, actor, clientSecretId, clientSecretName) =>
            appId shouldBe applicationId
            actor shouldBe expectedActor
            eventDateTime shouldBe timestamp
            clientSecretId shouldBe clientSecret.id
            clientSecretName shouldBe clientSecret.name
        }
      }
    }
  }

  "given a principal application" should {
    "succeed for an admin" in new Setup {
      val updatedApp = principalApp // Don't need the ClientSecrets fixed here
      ApplicationRepoMock.AddClientSecret.thenReturn(applicationId)(updatedApp)

      val result = await(underTest.process(principalApp, addClientSecretByAdmin).value).right.value

      checkSuccessResult(adminActor)(result)
    }

    "return an error for a non-admin developer" in new Setup {
      val result = await(underTest.process(principalApp, addClientSecretByDev).value).left.value.toNonEmptyList.toList

      result should have length 1
      result.head shouldBe "App is in PRODUCTION so User must be an ADMIN"
    }

    "return an error for a non-admin developer and application with full secrets" in new Setup(1) {
      val result = await(underTest.process(principalApp, addClientSecretByDev).value).left.value.toNonEmptyList.toList

      result should have length 2
      result should contain allOf (
        "App is in PRODUCTION so User must be an ADMIN",
        "Client secret limit has been exceeded"
      )
    }
  }

  "given a subordinate application" should {
    "succeed for a developer" in new Setup {
      val updatedApp = principalApp // Don't need the ClientSecrets fixed here
      ApplicationRepoMock.AddClientSecret.thenReturn(applicationId)(updatedApp)

      val result = await(underTest.process(subordinateApp, addClientSecretByDev).value).right.value

      checkSuccessResult(developerActor)(result)
    }
  }
}
