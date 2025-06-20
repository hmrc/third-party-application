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

package uk.gov.hmrc.thirdpartyapplication.services.commands.submission

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ImportantSubmissionData, PrivacyPolicyLocations, ResponsibleIndividual, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeResponsibleIndividualToSelf
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationStateExamples
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class ChangeResponsibleIndividualToSelfCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule with ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission  = aSubmission
    val oldRiUserId = adminTwo.userId
    val oldRiEmail  = adminTwo.emailAddress
    val oldRiName   = "old ri"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(oldRiName, oldRiEmail.text),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app       = storedApp.copy(
      collaborators = Set(adminOne, adminTwo),
      access = Access.Standard(List.empty, List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )
    val ts        = FixedClock.instant
    val riName    = "Mr Responsible"
    val riEmail   = "ri@example.com".toLaxEmail
    val underTest = new ChangeResponsibleIndividualToSelfCommandHandler(ApplicationRepoMock.aMock, SubmissionsServiceMock.aMock)

    val changeResponsibleIndividualToSelfCommand = ChangeResponsibleIndividualToSelf(adminOne.userId, instant, riName, riEmail)

    def checkSuccessResult(expectedActor: Actor, expectedPreviousEmail: LaxEmailAddress, expectedPreviousName: String)(fn: => CommandHandler.AppCmdResultT) = {
      val testThis = await(fn.value).value

      inside(testThis) { case (returnedApp, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ApplicationEvents.ResponsibleIndividualChangedToSelf(
                _,
                applicationId,
                eventDateTime,
                actor,
                previousResponsibleIndividualName,
                previousResponsibleIndividualEmail,
                submissionId,
                submissionIndex,
                requestingName,
                requestingEmail
              ) =>
            applicationId shouldBe app.id
            eventDateTime shouldBe ts
            actor shouldBe Actors.AppCollaborator(adminOne.emailAddress)
            previousResponsibleIndividualName shouldBe oldRiName
            previousResponsibleIndividualEmail shouldBe oldRiEmail
            submissionIndex shouldBe submission.latestInstance.index
            submissionId.value shouldBe submission.id.value
            requestingName shouldBe riName
            requestingEmail shouldBe adminOne.emailAddress
        }
      }
    }
  }

  "given a ChangeResponsibleIndividualToSelf command" should {
    "should be processed successfully" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      ApplicationRepoMock.UpdateApplicationChangeResponsibleIndividualToSelf.thenReturn(app) // Not modified

      checkSuccessResult(otherAdminAsActor, oldRiEmail, oldRiName)(underTest.process(app, changeResponsibleIndividualToSelfCommand))
    }

    "return an error if no submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      checkFailsWith(s"No submission found for application ${app.id.value}") {
        underTest.process(app, ChangeResponsibleIndividualToSelf(adminOne.userId, instant, riName, riEmail))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val nonStandardApp = app.withAccess(Access.Ropc(Set.empty))
      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(nonStandardApp, ChangeResponsibleIndividualToSelf(adminOne.userId, instant, riName, riEmail))
      }
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val oldJourneyApp = app.withAccess(Access.Standard(List.empty, List.empty, None, None, Set.empty, None, None))
      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(oldJourneyApp, ChangeResponsibleIndividualToSelf(adminOne.userId, instant, riName, riEmail))
      }
    }

    "return an error if the application is not approved" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val notApprovedApp = app.withState(ApplicationStateExamples.pendingGatekeeperApproval("someone@example.com", "Someone"))
      checkFailsWith("App is not in PRE_PRODUCTION or in PRODUCTION state") {
        underTest.process(notApprovedApp, ChangeResponsibleIndividualToSelf(adminOne.userId, instant, riName, riEmail))
      }
    }

    "return an error if the requester is not an admin for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      checkFailsWith("User must be an ADMIN") {
        underTest.process(app, ChangeResponsibleIndividualToSelf(UserId.random, instant, riName, riEmail))
      }
    }

    "return an error if the requester is already the RI for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      checkFailsWith(s"The specified individual is already the RI for this application") {
        underTest.process(app, ChangeResponsibleIndividualToSelf(oldRiUserId, instant, oldRiName, oldRiEmail))
      }
    }

  }
}
