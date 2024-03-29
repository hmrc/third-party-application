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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import java.time.format.DateTimeFormatter

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.{ActualAnswersAsText, QuestionsAndAnswersToMap}
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.mocks.services.TermsOfUseInvitationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class GrantApprovalsServiceSpec extends AsyncHmrcSpec {

  trait Setup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationServiceMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with SubmissionsServiceMockModule
      with EmailConnectorMockModule
      with ApplicationTestData
      with SubmissionsTestData {

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val fmt = DateTimeFormatter.ISO_DATE_TIME

    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")
    val acceptanceDate        = instant

    val acceptance = TermsOfUseAcceptance(
      responsibleIndividual,
      acceptanceDate,
      submissionId,
      0
    )

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List(acceptance)
    )

    val applicationPendingGKApproval: StoredApplication = anApplicationData(
      applicationId,
      pendingGatekeeperApprovalState("bob@fastshow.com"),
      access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val prodAppId = ApplicationId.random

    val applicationProduction: StoredApplication = anApplicationData(
      prodAppId,
      productionState("bob@fastshow.com"),
      access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val underTest =
      new GrantApprovalsService(
        AuditServiceMock.aMock,
        ApplicationRepoMock.aMock,
        StateHistoryRepoMock.aMock,
        TermsOfUseInvitationServiceMock.aMock,
        ResponsibleIndividualVerificationRepositoryMock.aMock,
        SubmissionsServiceMock.aMock,
        EmailConnectorMock.aMock,
        clock
      )
  }

  "GrantApprovalsService.grant" should {
    "grant the specified application" in new Setup {

      ApplicationRepoMock.Save.thenAnswer()
      StateHistoryRepoMock.Insert.thenAnswer()
      SubmissionsServiceMock.Store.thenReturn()
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()
      EmailConnectorMock.SendApplicationApprovedAdminConfirmation.thenReturnSuccess()

      val result = await(underTest.grant(applicationPendingGKApproval, submittedSubmission, gatekeeperUserName, None, None))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) if (app.state.name == State.PENDING_REQUESTER_VERIFICATION) =>
      }
      ApplicationRepoMock.Save.verifyCalled().state.name shouldBe State.PENDING_REQUESTER_VERIFICATION
      SubmissionsServiceMock.Store.verifyCalledWith().status.isGranted shouldBe true
      SubmissionsServiceMock.Store.verifyCalledWith().status should matchPattern {
        case Submission.Status.Granted(_, gatekeeperUserName, _, _) =>
      }

      val (someQuestionId, expectedAnswer) = submittedSubmission.latestInstance.answersToQuestions.head
      val someQuestionWording              = QuestionsAndAnswersToMap.stripSpacesAndCapitalise(submittedSubmission.findQuestion(someQuestionId).get.wording.value)

      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserName
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationApprovalGranted
      AuditServiceMock.AuditGatekeeperAction.verifyExtras().get("responsibleIndividual.verification.date").value shouldBe fmt.format(acceptanceDate.asLocalDateTime)
      AuditServiceMock.AuditGatekeeperAction.verifyExtras().get(someQuestionWording).value shouldBe ActualAnswersAsText(expectedAnswer)
    }

    "grant the specified application with warnings" in new Setup {
      ApplicationRepoMock.Save.thenAnswer()
      StateHistoryRepoMock.Insert.thenAnswer()
      SubmissionsServiceMock.Store.thenReturn()
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()
      EmailConnectorMock.SendApplicationApprovedAdminConfirmation.thenReturnSuccess()

      val warning     = Some("Here are some warnings")
      val escalatedTo = Some("Marty McFly")
      val result      = await(underTest.grant(applicationPendingGKApproval, submittedSubmission, gatekeeperUserName, warning, escalatedTo))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) if (app.state.name == State.PENDING_REQUESTER_VERIFICATION) =>
      }
      ApplicationRepoMock.Save.verifyCalled().state.name shouldBe State.PENDING_REQUESTER_VERIFICATION
      SubmissionsServiceMock.Store.verifyCalledWith().status.isGrantedWithWarnings shouldBe true
      SubmissionsServiceMock.Store.verifyCalledWith().status should matchPattern {
        case Submission.Status.GrantedWithWarnings(_, gatekeeperUserName, warning, escalatedTo) =>
      }

      val (someQuestionId, expectedAnswer) = submittedSubmission.latestInstance.answersToQuestions.head
      val someQuestionWording              = QuestionsAndAnswersToMap.stripSpacesAndCapitalise(submittedSubmission.findQuestion(someQuestionId).get.wording.value)

      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserName
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationApprovalGranted
      AuditServiceMock.AuditGatekeeperAction.verifyExtras().get(someQuestionWording).value shouldBe ActualAnswersAsText(expectedAnswer)
    }

    "fail to grant the specified application if the application is in the incorrect state" in new Setup {
      val result = await(underTest.grant(anApplicationData(applicationId, testingState()), answeredSubmission, gatekeeperUserName, None, None))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }

    "fail to grant the specified application if the submission is not in the submitted state" in new Setup {
      val result = await(underTest.grant(applicationPendingGKApproval, answeredSubmission, gatekeeperUserName, None, None))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectSubmissionState
    }
  }

  "GrantApprovalsService.grantWithWarningsForTouUplift" should {
    "grant the specified ToU application with warnings" in new Setup {

      SubmissionsServiceMock.Store.thenReturn()
      TermsOfUseInvitationServiceMock.UpdateStatus.thenReturn()

      val warning = "Here are some warnings"
      val result  = await(underTest.grantWithWarningsForTouUplift(applicationProduction, warningsSubmission, gatekeeperUserName, warning))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) =>
      }
      SubmissionsServiceMock.Store.verifyCalledWith().status.isGrantedWithWarnings shouldBe true
      SubmissionsServiceMock.Store.verifyCalledWith().status should matchPattern {
        case Submission.Status.GrantedWithWarnings(_, gatekeeperUserName, warning, None) =>
      }
    }

    "fail to grant the specified application if the application is in the incorrect state" in new Setup {
      val warning = "Here are some warnings"
      val result  = await(underTest.grantWithWarningsForTouUplift(anApplicationData(applicationId, testingState()), warningsSubmission, gatekeeperUserName, warning))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }

    "fail to grant the specified application if the submission is not in the warnings state" in new Setup {
      val warning = "Here are some warnings"
      val result  = await(underTest.grantWithWarningsForTouUplift(applicationProduction, answeredSubmission, gatekeeperUserName, warning))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectSubmissionState
    }
  }

  "GrantApprovalsService.grantForTouUplift" should {
    val escalatedTo = Some("Marty McFly")

    "grant the specified ToU application" in new Setup {

      SubmissionsServiceMock.Store.thenReturn()
      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(applicationProduction)
      EmailConnectorMock.SendNewTermsOfUseConfirmation.thenReturnSuccess()
      TermsOfUseInvitationServiceMock.UpdateStatus.thenReturn()

      val result = await(underTest.grantForTouUplift(applicationProduction, grantedWithWarningsSubmission, gatekeeperUserName, reasons, escalatedTo))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) =>
      }
      SubmissionsServiceMock.Store.verifyCalledWith().status.isGranted shouldBe true
      SubmissionsServiceMock.Store.verifyCalledWith().status should matchPattern {
        case Submission.Status.Granted(_, gatekeeperUserName, _, _) =>
      }
    }

    "fail to grant the specified application if the application is in the incorrect state" in new Setup {
      val result = await(underTest.grantForTouUplift(anApplicationData(applicationId, testingState()), warningsSubmission, gatekeeperUserName, reasons, escalatedTo))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }

    "fail to grant the specified application if the submission is not in the granted with warnings state" in new Setup {
      val result = await(underTest.grantForTouUplift(applicationProduction, answeredSubmission, gatekeeperUserName, reasons, escalatedTo))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectSubmissionState
    }
  }

  "GrantApprovalsService.declineForTouUplift" should {
    "decline the specified ToU application" in new Setup {

      SubmissionsServiceMock.Store.thenReturn()
      TermsOfUseInvitationServiceMock.UpdateResetBackToEmailSent.thenReturn()

      val warning = "Here are some warnings"
      val result  = await(underTest.declineForTouUplift(applicationProduction, failSubmission, gatekeeperUserName, warning))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) =>
      }
      SubmissionsServiceMock.Store.verifyCalledWith().status.isAnswering shouldBe true
      SubmissionsServiceMock.Store.verifyCalledWith().status should matchPattern {
        case Submission.Status.Answering(_, gatekeeperUserName) =>
      }
    }

    "fail to decline the specified application if the application is in the incorrect state" in new Setup {
      val warning = "Here are some warnings"
      val result  = await(underTest.declineForTouUplift(anApplicationData(applicationId, testingState()), failSubmission, gatekeeperUserName, warning))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }

    "fail to decline the specified application if the submission is not in the fails state" in new Setup {
      val warning = "Here are some warnings"
      val result  = await(underTest.declineForTouUplift(applicationProduction, answeredSubmission, gatekeeperUserName, warning))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectSubmissionState
    }
  }

  "GrantApprovalsService.resetForTouUplift" should {
    "reset the specified ToU application" in new Setup {

      SubmissionsServiceMock.Store.thenReturn()
      TermsOfUseInvitationServiceMock.UpdateResetBackToEmailSent.thenReturn()
      ResponsibleIndividualVerificationRepositoryMock.DeleteSubmissionInstance.succeeds()

      val warning = "Here are some warnings"
      val result  = await(underTest.resetForTouUplift(applicationProduction, pendingRISubmission, gatekeeperUserName, warning))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) =>
      }
      SubmissionsServiceMock.Store.verifyCalledWith().status.isAnswering shouldBe true
      SubmissionsServiceMock.Store.verifyCalledWith().status should matchPattern {
        case Submission.Status.Answering(_, gatekeeperUserName) =>
      }
    }

    "fail to decline the specified application if the application is in the incorrect state" in new Setup {
      val warning = "Here are some warnings"
      val result  = await(underTest.resetForTouUplift(anApplicationData(applicationId, testingState()), pendingRISubmission, gatekeeperUserName, warning))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }
  }
}
