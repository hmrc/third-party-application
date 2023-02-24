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

import cats.data.{NonEmptyChain, Validated}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, FixedClock, HmrcSpec}
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
class CommandHandlerSpec extends HmrcSpec with ApplicationTestData {
  
  import CommandHandler._
  import CommandFailures._

  val applicationId = ApplicationId.random
  val timestamp     = FixedClock.now
  val clientSecret  = ClientSecret("name", timestamp, hashedSecret = "hashed")
  val secretValue   = "somSecret"
  
  // Application with two client secrets
  val applicationData: ApplicationData = anApplicationData(applicationId)

  def checkSuccess[T](expected: T)(fn: => Validated[CommandHandler.Failures, T]) = {
    fn shouldBe Validated.Valid(expected)
  }

  def checkFailsWith(failure: CommandFailure, failures: CommandFailure*)(fn: => Validated[CommandHandler.Failures, _]) = {
    fn shouldBe Validated.Invalid(NonEmptyChain.of(failure, failures: _*))
  }

  "appHasLessThanLimitOfSecrets" should {
    "pass when existing secrets are less than the limit" in {
      checkSuccess(()) {
        appHasLessThanLimitOfSecrets(applicationData, 3)
      }

    }
    "fail when existing secrets are at the limit" in {
      checkFailsWith(GenericFailure("Client secret limit has been exceeded")) {
        appHasLessThanLimitOfSecrets(applicationData, 2)
      }
    }
  }

  "isCollaboratorOnApp" should {

    "pass when collaborator exists" in {
      val theCollaborator = developerCollaborator

      val app = applicationData.copy(collaborators = Set(theCollaborator))

      checkSuccess(()) {
        isCollaboratorOnApp(theCollaborator, app)
      }
    }

    "fail with CollaboratorDoesNotExistOnApp when collaborator is not on app" in {
      val theCollaborator = developerCollaborator

      val app = applicationData.copy(collaborators = Set())

      checkFailsWith(CollaboratorDoesNotExistOnApp) {
        isCollaboratorOnApp(theCollaborator, app)
      }
    }

    "fail with CollaboratorHasMismatchOnApp when collaborator is found by email on app but ids are not the same" in {
      val theCollaborator = developerCollaborator
      
      val app = applicationData.copy(collaborators = Set(theCollaborator.copy(userId = UserId.random)))
      
      checkFailsWith(CollaboratorHasMismatchOnApp) {
        isCollaboratorOnApp(theCollaborator, app)
       }
    }
 
    "fail with CollaboratorHasMismatchOnApp when collaborator is found by id on app but emails are not the same" in {
      val theCollaborator = developerCollaborator

      val app = applicationData.copy(collaborators = Set(theCollaborator.copy(emailAddress = anAdminEmail)))

      isCollaboratorOnApp(theCollaborator, app) shouldBe Validated.Invalid(NonEmptyChain(CommandFailures.CollaboratorHasMismatchOnApp))
    }
  }
}
