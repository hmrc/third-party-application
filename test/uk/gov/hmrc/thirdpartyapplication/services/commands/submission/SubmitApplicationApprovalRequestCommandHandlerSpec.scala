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
import scala.concurrent.Future.successful

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.ResponsibleIndividualVerificationServiceMockModule
import uk.gov.hmrc.apiplatform.modules.approvals.services.ApprovalsNamingService
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SubmitApplicationApprovalRequest
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.CommandFailures
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.{ApplicationApprovalRequestSubmitted, ResponsibleIndividualVerificationRequired}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.AnswerQuestion
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, DuplicateName, InvalidName, ValidName}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class SubmitApplicationApprovalRequestCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule
      with ResponsibleIndividualVerificationServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission               = buildFullyAnsweredSubmission(aSubmission)
    val submissionRequesterNotRI = AnswerQuestion.recordAnswer(submission, submission.questionIdsOfInterest.responsibleIndividualIsRequesterId, List("No")).value.submission

    val appAdminUserId = UserId.random
    val appAdminEmail  = "admin@example.com".toLaxEmail
    val appAdminName   = "Ms Admin"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build("Bob", "bob@example.com"),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app = anApplicationData(applicationId).copy(
      state = ApplicationStateExamples.testing,
      access = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )

    val ts                                                 = FixedClock.instant
    val mockApprovalsNamingService: ApprovalsNamingService = mock[ApprovalsNamingService]

    def namingServiceReturns(result: ApplicationNameValidationResult) =
      when(mockApprovalsNamingService.validateApplicationName(*, *[ApplicationId])).thenReturn(successful(result))

    val underTest = new SubmitApplicationApprovalRequestCommandHandler(
      SubmissionsServiceMock.aMock,
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      TermsOfUseInvitationRepositoryMock.aMock,
      mockApprovalsNamingService,
      ResponsibleIndividualVerificationServiceMock.aMock
    )
  }

  "process" should {
    "create correct event for a valid request with a standard app and submission where requester is the responsible individual" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      namingServiceReturns(ValidName)
      ApplicationRepoMock.Save.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      SubmissionsServiceMock.Store.thenReturn()
      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(app)

      val result = await(underTest.process(app, SubmitApplicationApprovalRequest(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: ApplicationApprovalRequestSubmitted =>
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
      namingServiceReturns(ValidName)
      ApplicationRepoMock.Save.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      SubmissionsServiceMock.Store.thenReturn()
      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(app)
      val code = ResponsibleIndividualVerificationId.random
      ResponsibleIndividualVerificationServiceMock.CreateNewTouUpliftVerification.thenCreateNewTouUpliftVerification(code)

      val result = await(underTest.process(app, SubmitApplicationApprovalRequest(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail)).value).value

      inside(result) { case (_, events) =>
        events should have size 2

        inside(events.head) {
          case event: ApplicationApprovalRequestSubmitted =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
        }

        inside(events.tail.head) {
          case event: ResponsibleIndividualVerificationRequired =>
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
      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      checkFailsWith(s"No submission found for application $applicationId") {
        underTest.process(app, SubmitApplicationApprovalRequest(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      namingServiceReturns(ValidName)
      val nonStandardApp = app.copy(access = Access.Ropc(Set.empty))

      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(nonStandardApp, SubmitApplicationApprovalRequest(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }

    "return an error if the application name is not unique" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      namingServiceReturns(DuplicateName)

      checkFailsWith(CommandFailures.DuplicateApplicationName) {
        underTest.process(app, SubmitApplicationApprovalRequest(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }

    "return an error if the application name is invalid" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      namingServiceReturns(InvalidName)

      checkFailsWith(CommandFailures.InvalidApplicationName) {
        underTest.process(app, SubmitApplicationApprovalRequest(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }

    "return an error if the application is not in TESTING" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      namingServiceReturns(ValidName)
      val notTestingApp = app.copy(state = ApplicationStateExamples.pendingGatekeeperApproval("someone@example.com", "Someone"))

      checkFailsWith("App is not in TESTING state") {
        underTest.process(notTestingApp, SubmitApplicationApprovalRequest(Actors.AppCollaborator(appAdminEmail), instant, appAdminName, appAdminEmail))
      }
    }
  }
}
