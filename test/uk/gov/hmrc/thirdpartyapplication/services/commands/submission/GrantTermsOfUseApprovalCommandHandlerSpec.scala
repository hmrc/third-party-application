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
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.GrantTermsOfUseApproval
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.TermsOfUseApprovalGranted
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class GrantTermsOfUseApprovalCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission = grantedWithWarningsSubmission

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

    val app = storedApp.copy(
      state = ApplicationStateExamples.production(appAdminEmail.text, appAdminName),
      access = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )

    val ts = FixedClock.instant

    val underTest = new GrantTermsOfUseApprovalCommandHandler(
      ApplicationRepoMock.aMock,
      TermsOfUseInvitationRepositoryMock.aMock,
      SubmissionsServiceMock.aMock,
      FixedClock.clock
    )
  }

  "process" should {
    val escalatedTo = Some("The boss")

    "create correct event for a valid request" in new Setup {

      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(app)
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()
      SubmissionsServiceMock.Store.thenReturn()

      val result = await(underTest.process(app, GrantTermsOfUseApproval(gkUserEmail, instant, reasons, escalatedTo)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: TermsOfUseApprovalGranted =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gkUserEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.reasons shouldBe reasons
            event.escalatedTo shouldBe escalatedTo
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
        }
      }
    }

    "return an error if no submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      checkFailsWith(s"No submission found for application $applicationId") {
        underTest.process(app, GrantTermsOfUseApproval(gkUserEmail, instant, reasons, escalatedTo))
      }
    }

    "return an error if submission has not been submitted" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(answeringSubmission)

      checkFailsWith("Rejected due to incorrect submission state") {
        underTest.process(app, GrantTermsOfUseApproval(gkUserEmail, instant, reasons, escalatedTo))
      }

    }

    "return an error if the application is not in PRODUCTION" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val testingApp = app.copy(state = ApplicationStateExamples.testing)

      checkFailsWith("App is not in PRODUCTION state", "No requestedBy email found") {
        underTest.process(testingApp, GrantTermsOfUseApproval(gkUserEmail, instant, reasons, escalatedTo))
      }

    }

    "return an error if the unable to get responsibleIndividual" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val testingApp = app.copy(access = Access.Standard(List.empty, None, None, Set.empty, None, None))
      checkFailsWith("The responsible individual has not been set for this application") {
        underTest.process(testingApp, GrantTermsOfUseApproval(gkUserEmail, instant, reasons, escalatedTo))
      }

    }
  }
}
