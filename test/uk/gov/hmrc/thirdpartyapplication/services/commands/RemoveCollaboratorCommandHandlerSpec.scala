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
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{CollaboratorActor, CollaboratorAdded, CollaboratorRemoved, GatekeeperUserActor, ScheduledJobActor}
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


    val app = anApplicationData(applicationId).copy(
      collaborators = Set(
        developerCollaborator,
        adminCollaborator
      )
    )

    val timestamp = LocalDateTime.now
    val collaboratorEmail = "newdev@somecompany.com"

    val collaborator = Collaborator(collaboratorEmail, Role.DEVELOPER, idOf(collaboratorEmail))
    val adminsToEmail = Set(adminEmail, devEmail)

    val removeCollaborator = RemoveCollaborator(idOf(adminActor.email), adminEmail, collaborator, adminsToEmail, timestamp)

    val removeCollaboratorGK = RemoveCollaboratorGateKeeper(gkUserEmail, collaborator, adminsToEmail, timestamp)

    val removeCollaboratorPJ = RemoveCollaboratorPlatformJobs(jobId, collaborator, adminsToEmail, timestamp)
  }

  "process RemoveCollaborator" should {
    "create a valid event for a standard command" in new Setup {
      val result = await(underTest.process(app, removeCollaborator))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[CollaboratorRemoved]
      event.applicationId shouldBe applicationId
      event.actor shouldBe adminActor
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

  "process RemoveCollaboratorGateKeeper" should {
    "create a valid event for a Gatekeeper command" in new Setup {
      val result = await(underTest.process(app, removeCollaboratorGK))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[CollaboratorRemoved]
      event.applicationId shouldBe applicationId
      event.actor shouldBe gkUserActor
      event.eventDateTime shouldBe timestamp
      event.collaboratorEmail shouldBe collaborator.emailAddress
      event.collaboratorId shouldBe collaborator.userId
      event.collaboratorRole shouldBe collaborator.role
    }

    "return an error when trying to remove last admin for an application" in new Setup {
      val result: ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]] = await(underTest.process(app, removeCollaboratorGK.copy(collaborator = adminCollaborator)))

      result.isValid shouldBe false
      result.toEither match {
        case Left(Chain(error: String)) => error shouldBe s"Collaborator is last remaining admin for Application ${app.id.asText}"
        case _ => fail()
      }

    }
  }

  "process RemoveCollaboratorPlatformJobs" should {
    "create a valid event for a Gatekeeper command" in new Setup {
      val result = await(underTest.process(app, removeCollaboratorPJ))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[CollaboratorRemoved]
      event.applicationId shouldBe applicationId
      event.actor shouldBe scheduledJobActor
      event.eventDateTime shouldBe timestamp
      event.collaboratorEmail shouldBe collaborator.emailAddress
      event.collaboratorId shouldBe collaborator.userId
      event.collaboratorRole shouldBe collaborator.role
    }

    "return an error when trying to remove last admin for an application" in new Setup {
      val result: ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]] = await(underTest.process(app, removeCollaboratorPJ.copy(collaborator = adminCollaborator)))

      result.isValid shouldBe false
      result.toEither match {
        case Left(Chain(error: String)) => error shouldBe s"Collaborator is last remaining admin for Application ${app.id.asText}"
        case _ => fail()
      }

    }
  }


}
