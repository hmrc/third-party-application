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
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.ResponsibleIndividualVerificationServiceMockModule
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SubmitTermsOfUseApproval
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.{ResponsibleIndividualVerificationStarted, TermsOfUseApprovalSubmitted, TermsOfUsePassed}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.AnswerQuestion
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class SubmitTermsOfUseApprovalCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule
      with ResponsibleIndividualVerificationServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission               = buildFullyAnsweredSubmission(aSubmission)
    val submissionRequesterNotRI = AnswerQuestion.recordAnswer(submission, submission.questionIdsOfInterest.responsibleIndividualIsRequesterId, List("No")).value.submission
    val submissionWithFail       = AnswerQuestion.recordAnswer(submission, submission.questionIdsOfInterest.privacyPolicyId, List("No")).value.submission

    val appAdminUserId = UserId.random
    val appAdminEmail  = "admin@example.com".toLaxEmail
    val appAdminName   = "Ms Admin"

    val termsOfUseInvitation = TermsOfUseInvitation(applicationId, instant, instant, instant, None, TermsOfUseInvitationState.EMAIL_SENT)

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build("Bob", "bob@example.com"),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app = storedApp.copy(
      state = ApplicationStateExamples.production("bob@example.com", "Bob"),
      access = Access.Standard(List.empty, List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )

    val ts = FixedClock.instant

    val underTest = new SubmitTermsOfUseApprovalCommandHandler(
      SubmissionsServiceMock.aMock,
      ApplicationRepoMock.aMock,
      TermsOfUseInvitationRepositoryMock.aMock,
      ResponsibleIndividualVerificationServiceMock.aMock
    )
  }

  "process" should {
    "create correct event for a valid request with a standard app and submission  will pass where requester is the responsible individual" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(termsOfUseInvitation)
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      ApplicationRepoMock.Save.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      SubmissionsServiceMock.Store.thenReturn()
      ApplicationRepoMock.UpdateApplicationImportantSubmissionData.succeeds()

      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(app)

      val result = await(underTest.process(app, SubmitTermsOfUseApproval(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail)).value).value

      inside(result) { case (_, events) =>
        events should have size 2

        inside(events.head) {
          case event: TermsOfUseApprovalSubmitted =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
        }

        inside(events.tail.head) {
          case event: TermsOfUsePassed =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
        }

      }
    }

    "create correct event for a valid request with a standard app and submission will fail where requester is the responsible individual" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(termsOfUseInvitation)
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()

      SubmissionsServiceMock.FetchLatest.thenReturn(submissionWithFail)
      ApplicationRepoMock.Save.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      SubmissionsServiceMock.Store.thenReturnWith(failSubmission)
      ApplicationRepoMock.UpdateApplicationImportantSubmissionData.succeeds()

      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(app)

      val result = await(underTest.process(app, SubmitTermsOfUseApproval(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: TermsOfUseApprovalSubmitted =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
        }

      }
    }

    "create correct event for a valid request with a standard app and submission where requester is NOT the responsible individual" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submissionRequesterNotRI)
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(termsOfUseInvitation)
      ApplicationRepoMock.Save.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      SubmissionsServiceMock.Store.thenReturn()
      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(app)
      ApplicationRepoMock.UpdateApplicationImportantSubmissionData.succeeds()

      val code = ResponsibleIndividualVerificationId.random
      ResponsibleIndividualVerificationServiceMock.CreateNewTouUpliftVerification.thenCreateNewTouUpliftVerification(code)

      val result = await(underTest.process(app, SubmitTermsOfUseApproval(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail)).value).value

      inside(result) { case (_, events) =>
        events should have size 2

        inside(events.head) {
          case event: TermsOfUseApprovalSubmitted =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
        }

        inside(events.tail.head) {
          case event: ResponsibleIndividualVerificationStarted =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
            event.verificationId shouldBe code.value
        }
      }
    }

    "return an error if no submission is found for the application" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(termsOfUseInvitation)
      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      checkFailsWith(s"No submission/termsOfUseInvitation found for application $applicationId") {
        underTest.process(app, SubmitTermsOfUseApproval(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(termsOfUseInvitation)
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val nonStandardApp = app.withAccess(Access.Ropc(Set.empty))

      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(nonStandardApp, SubmitTermsOfUseApproval(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }

    "return an error if submission has not been answered completely" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(termsOfUseInvitation)
      val partiallyAnsweredSubmission = buildPartiallyAnsweredSubmission(submission)
      SubmissionsServiceMock.FetchLatest.thenReturn(partiallyAnsweredSubmission)

      checkFailsWith("Submission has not been answered completely") {
        underTest.process(app, SubmitTermsOfUseApproval(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }

    "return an error if the application is not in PRODUCTION" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(termsOfUseInvitation)
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val notTestingApp = app.withState(ApplicationStateExamples.pendingGatekeeperApproval("someone@example.com", "Someone"))

      checkFailsWith("App is not in PRODUCTION state") {
        underTest.process(notTestingApp, SubmitTermsOfUseApproval(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }
  }
}
