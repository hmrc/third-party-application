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

import cats.data.{Chain, NonEmptyList, ValidatedNec}

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ClientSecretAdded, CollaboratorActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

class AddClientSecretCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData {

  trait Setup {
    val underTest = new AddClientSecretCommandHandler()

    val applicationId = ApplicationId.random
    val adminEmail    = "admin@example.com"

    val developerUserId       = idOf(devEmail)
    val developerCollaborator = Collaborator(devEmail, Role.DEVELOPER, developerUserId)
    val developerActor        = CollaboratorActor(devEmail)

    val adminUserId       = idOf(adminEmail)
    val adminCollaborator = Collaborator(adminEmail, Role.ADMINISTRATOR, adminUserId)
    val adminActor        = CollaboratorActor(adminEmail)

    val app = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator
      )
    )

    val timestamp    = FixedClock.now
    val secretValue  = "secret"
    val clientSecret = ClientSecret("name", timestamp, hashedSecret = "hashed")

    val addClientSecretByDev   = AddClientSecret(CollaboratorActor(devEmail), secretValue, clientSecret, timestamp)
    val addClientSecretByAdmin = AddClientSecret(CollaboratorActor(adminEmail), secretValue, clientSecret, timestamp)
  }

  "process" should {
    "create a valid event for an admin on a production application" in new Setup {
      val result = await(underTest.process(app, addClientSecretByAdmin))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ClientSecretAdded]
      event.applicationId shouldBe applicationId
      event.actor shouldBe adminActor
      event.eventDateTime shouldBe timestamp
      event.secretValue shouldBe secretValue
      event.clientSecret shouldBe clientSecret
    }

    "return an error for a non-admin developer on a production application" in new Setup {
      val result: ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]] = await(underTest.process(app, addClientSecretByDev))

      result.isValid shouldBe false
      result.toEither match {
        case Left(Chain(error: String)) => error shouldBe "App is in PRODUCTION so User must be an ADMIN"
        case _                          => fail()
      }

    }

    "create a valid event for a developer on a non production application" in new Setup {
      val nonProductionApp = app.copy(environment = Environment.SANDBOX.toString)
      val result           = await(underTest.process(nonProductionApp, addClientSecretByDev))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ClientSecretAdded]
      event.applicationId shouldBe nonProductionApp.id
      event.actor shouldBe developerActor
      event.eventDateTime shouldBe timestamp
      event.secretValue shouldBe secretValue
      event.clientSecret shouldBe clientSecret
    }
  }

}
