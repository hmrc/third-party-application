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
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.GrantApplicationApprovalRequest
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.{ApplicationApprovalRequestGranted, ApplicationApprovalRequestGrantedWithWarnings}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class GrantApplicationApprovalRequestCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission = submittedSubmission

    val appAdminUserId = UserId.random
    val appAdminEmail  = "bob@example.com".toLaxEmail
    val appAdminName   = "Bob"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build("Bob", "bob@example.com"),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app = anApplicationData().copy(
      state = ApplicationStateExamples.pendingGatekeeperApproval(appAdminEmail.text, appAdminName),
      access = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )

    val ts = FixedClock.instant

    val underTest = new GrantApplicationApprovalRequestCommandHandler(
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      SubmissionsServiceMock.aMock,
      FixedClock.clock
    )
  }

  "process" should {

    "create correct event for a valid request with no warnings" in new Setup {

      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      ApplicationRepoMock.Save.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      SubmissionsServiceMock.Store.thenReturn()

      val result = await(underTest.process(app, GrantApplicationApprovalRequest(gkUserEmail, instant, None, None)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: ApplicationApprovalRequestGranted =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gkUserEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
        }
      }
    }

    "create correct event for a valid request with warnings" in new Setup {

      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      ApplicationRepoMock.Save.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      SubmissionsServiceMock.Store.thenReturn()

      val warnings    = "warnings!!!!"
      val escalatedTo = "The boss"
      val result      = await(underTest.process(app, GrantApplicationApprovalRequest(gkUserEmail, instant, Some(warnings), Some(escalatedTo))).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: ApplicationApprovalRequestGrantedWithWarnings =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gkUserEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
            event.warnings shouldBe warnings
            event.escalatedTo shouldBe Some(escalatedTo)
        }
      }
    }

    "return an error if no submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      checkFailsWith(s"No submission or important submission data found for application $applicationId") {
        underTest.process(app, GrantApplicationApprovalRequest(gkUserEmail, instant, None, None))
      }
    }

    "return an error if important submission data not found" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val nonStandardApp = app.copy(access = Access.Standard(List.empty, None, None, Set.empty, None, None))

      checkFailsWith(s"No submission or important submission data found for application $applicationId") {
        underTest.process(nonStandardApp, GrantApplicationApprovalRequest(gkUserEmail, instant, None, None))
      }

    }

    "return an error if submission has not been submitted" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(answeringSubmission)

      checkFailsWith("Submission has not been submitted") {
        underTest.process(app, GrantApplicationApprovalRequest(gkUserEmail, instant, None, None))
      }

    }

    "return an error if the application is not in PENDING_GATEKEEPER_APPROVAL" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val testingApp = app.copy(state = ApplicationStateExamples.testing)

      checkFailsWith("App is not in PENDING_GATEKEEPER_APPROVAL state", "No requestedBy email found", "No requestedBy name found") {
        underTest.process(testingApp, GrantApplicationApprovalRequest(gkUserEmail, instant, None, None))
      }

    }
  }
}
