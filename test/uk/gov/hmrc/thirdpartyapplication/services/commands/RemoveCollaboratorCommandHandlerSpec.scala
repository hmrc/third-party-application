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

import cats.data.{Chain, NonEmptyList, ValidatedNec}
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{CollaboratorActor, CollaboratorRemoved, GatekeeperUserActor, ScheduledJobActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class RemoveCollaboratorCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData {

  trait Setup {
    val underTest = new RemoveCollaboratorCommandHandler()

    val applicationId = ApplicationId.random
    val adminEmail = "admin@example.com"

    val developerCollaborator = Collaborator(devEmail, Role.DEVELOPER, idOf(devEmail))


    val adminCollaborator = Collaborator(adminEmail, Role.ADMINISTRATOR, idOf(adminEmail))
    val adminActor = CollaboratorActor(adminEmail)

    val gkUserEmail = "admin@gatekeeper"
    val gkUserActor = GatekeeperUserActor(gkUserEmail)

    val jobId = "theJobThatDeletesCollaborators"
    val scheduledJobActor = ScheduledJobActor(jobId)
    val collaboratorEmail = "newdev@somecompany.com"

    val collaborator = Collaborator(collaboratorEmail, Role.DEVELOPER, idOf(collaboratorEmail))

    val app = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator,
        collaborator
      )
    )

    val timestamp = LocalDateTime.now

    val adminsToEmail = Set(adminEmail, devEmail)

    val removeCollaborator = RemoveCollaborator(CollaboratorActor(adminActor.email), collaborator, adminsToEmail, timestamp)
  }

  "process RemoveCollaborator" should {
    "create a valid event for a standard command with CollaboratorActor" in new Setup {
      val result = await(underTest.process(app, removeCollaborator))

      result.isValid shouldBe true
      val event = result.toOption.get.asInstanceOf[CollaboratorRemoved]
      event.applicationId shouldBe applicationId
      event.actor shouldBe adminActor
      event.eventDateTime shouldBe timestamp
      event.collaboratorEmail shouldBe collaborator.emailAddress
      event.collaboratorId shouldBe collaborator.userId
      event.collaboratorRole shouldBe collaborator.role
    }

    "create a valid event for a standard command with GatekeeperActor" in new Setup {
      val result = await(underTest.process(app, removeCollaborator.copy(actor = gkUserActor)))

      result.isValid shouldBe true
      val event = result.toOption.get.asInstanceOf[CollaboratorRemoved]
      event.applicationId shouldBe applicationId
      event.actor shouldBe gkUserActor
      event.eventDateTime shouldBe timestamp
      event.collaboratorEmail shouldBe collaborator.emailAddress
      event.collaboratorId shouldBe collaborator.userId
      event.collaboratorRole shouldBe collaborator.role
    }

    "return an error when trying to remove last admin for an application" in new Setup {
      val result: ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]] = await(underTest.process(app, removeCollaborator.copy(collaborator = adminCollaborator)))

      result.isValid shouldBe false
      result.toEither match {
        case Left(Chain(error: String)) => error shouldBe s"Collaborator is last remaining admin for Application ${app.id.asText}"
        case _ => fail()
      }

    }
  }
}
