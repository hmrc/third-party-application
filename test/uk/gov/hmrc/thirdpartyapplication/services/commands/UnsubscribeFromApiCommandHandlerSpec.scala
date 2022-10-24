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

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ApiUnsubscribed, CollaboratorActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class UnsubscribeFromApiCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with ApiIdentifierSyntax {

  trait Setup {
    val underTest = new UnsubscribeFromApiCommandHandler()

    val applicationId = ApplicationId.random

    val developerEmail = "developer@example.com"
    val developerUserId = idOf(developerEmail)
    val developerCollaborator = Collaborator(developerEmail, Role.DEVELOPER, developerUserId)
    val developerActor = CollaboratorActor(developerEmail)

    val app = anApplicationData(applicationId).copy(collaborators = Set(developerCollaborator))

    val apiIdentifier = "some-context".asIdentifier("1.1")
    val timestamp = LocalDateTime.now

    val unsubscribeFromApi = UnsubscribeFromApi(developerActor, apiIdentifier, timestamp)
  }

  "process" should {
    "create a valid event for an admin" in new Setup {
      val result = await(underTest.process(app, unsubscribeFromApi))

      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ApiUnsubscribed]
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

}