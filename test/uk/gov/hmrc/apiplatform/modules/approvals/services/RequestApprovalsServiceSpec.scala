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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.mocks.ResponsibleIndividualVerificationServiceMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{NoAnswer, SingleChoiceAnswer, Submission, TextAnswer}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.SubmissionDataExtracter
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, Standard, State}
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationServiceMockModule, AuditServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, DuplicateName, InvalidName, ValidName}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}

class RequestApprovalsServiceSpec extends AsyncHmrcSpec {

  trait Setup
      extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with SubmissionsServiceMockModule
      with EmailConnectorMockModule
      with ResponsibleIndividualVerificationServiceMockModule
      with ApplicationServiceMockModule
      with SubmissionsTestData
      with ApplicationTestData
      with FixedClock {

    val application: ApplicationData = anApplicationData(applicationId, testingState())

    val mockApprovalsNamingService: ApprovalsNamingService = mock[ApprovalsNamingService]

    def namingServiceReturns(result: ApplicationNameValidationResult) =
      when(mockApprovalsNamingService.validateApplicationNameAndAudit(*, *[ApplicationId], *)(*)).thenReturn(successful(result))

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val underTest = new RequestApprovalsService(
      AuditServiceMock.aMock,
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      mockApprovalsNamingService,
      SubmissionsServiceMock.aMock,
      EmailConnectorMock.aMock,
      ResponsibleIndividualVerificationServiceMock.aMock,
      ApplicationServiceMock.aMock,
      clock
    )
  }

  "RequestApprovalsService" when {
    "requestApproval" should {

      "update state, save and audit with RI details in questionnaire answers" in new Setup {
        namingServiceReturns(ValidName)
        val fakeSavedApplication = application.copy(normalisedName = "somethingElse")
        ApplicationRepoMock.Save.thenReturn(fakeSavedApplication)
        StateHistoryRepoMock.Insert.thenAnswer()
        AuditServiceMock.Audit.thenReturnSuccess()
        SubmissionsServiceMock.Store.thenReturn()
        EmailConnectorMock.SendVerifyResponsibleIndividualNotification.thenReturnSuccess()
        ResponsibleIndividualVerificationServiceMock.CreateNewVerification.thenCreateNewVerification()

        val questionsRiName                 = "andy pandy"
        val questionsRiEmail                = "andy@pandy.com"
        val answersWithRIDetails            = answersToQuestions
          .updated(testQuestionIdsOfInterest.responsibleIndividualIsRequesterId, SingleChoiceAnswer("No"))
          .updated(testQuestionIdsOfInterest.responsibleIndividualEmailId, TextAnswer(questionsRiEmail))
          .updated(testQuestionIdsOfInterest.responsibleIndividualNameId, TextAnswer(questionsRiName))
        val answeredSubmissionWithRIDetails = answeredSubmission.hasCompletelyAnsweredWith(answersWithRIDetails)

        val result = await(underTest.requestApproval(application, answeredSubmissionWithRIDetails, requestedByName, requestedByEmail))

        result shouldBe RequestApprovalsService.ApprovalAccepted(fakeSavedApplication)
        val savedStateHistory     = StateHistoryRepoMock.Insert.verifyCalled()
        savedStateHistory.state shouldBe State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
        AuditServiceMock.Audit.verifyCalled()
        ApplicationServiceMock.AddTermsOfUseAcceptance.verifyNeverCalled()
        val savedAppData          = ApplicationRepoMock.Save.verifyCalled()
        savedStateHistory.previousState shouldBe Some(State.TESTING)
        savedAppData.state.name shouldBe State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
        val responsibleIndividual = savedAppData.access.asInstanceOf[Standard].importantSubmissionData.get.responsibleIndividual
        responsibleIndividual.fullName.value shouldBe questionsRiName
        responsibleIndividual.emailAddress.value shouldBe questionsRiEmail

        val updatedSubmission = SubmissionsServiceMock.Store.verifyCalledWith()
        updatedSubmission.status should matchPattern {
          case Submission.Status.Submitted(_, requestedByEmail) =>
        }
      }

      "update state, save and audit with RI details not in questionnaire answers" in new Setup {
        namingServiceReturns(ValidName)
        val fakeSavedApplication = application.copy(normalisedName = "somethingElse")
        ApplicationRepoMock.Save.thenReturn(fakeSavedApplication)
        StateHistoryRepoMock.Insert.thenAnswer()
        AuditServiceMock.Audit.thenReturnSuccess()
        SubmissionsServiceMock.Store.thenReturn()
        ApplicationServiceMock.AddTermsOfUseAcceptance.thenReturn(fakeSavedApplication)

        val answersWithoutRIDetails            = answersToQuestions
          .updated(testQuestionIdsOfInterest.responsibleIndividualIsRequesterId, SingleChoiceAnswer("Yes"))
          .updated(testQuestionIdsOfInterest.responsibleIndividualEmailId, NoAnswer)
          .updated(testQuestionIdsOfInterest.responsibleIndividualNameId, NoAnswer)
        val answeredSubmissionWithoutRIDetails = answeredSubmission.hasCompletelyAnsweredWith(answersWithoutRIDetails)

        val result = await(underTest.requestApproval(application, answeredSubmissionWithoutRIDetails, requestedByName, requestedByEmail))

        result shouldBe RequestApprovalsService.ApprovalAccepted(fakeSavedApplication)
        val savedStateHistory     = StateHistoryRepoMock.Insert.verifyCalled()
        savedStateHistory.previousState shouldBe Some(State.TESTING)
        savedStateHistory.state shouldBe State.PENDING_GATEKEEPER_APPROVAL
        AuditServiceMock.Audit.verifyCalled()
        val savedAppData          = ApplicationRepoMock.Save.verifyCalled()
        savedAppData.state.name shouldBe State.PENDING_GATEKEEPER_APPROVAL
        val responsibleIndividual = savedAppData.access.asInstanceOf[Standard].importantSubmissionData.get.responsibleIndividual
        responsibleIndividual.fullName.value shouldBe requestedByName
        responsibleIndividual.emailAddress.value shouldBe requestedByEmail

        val updatedSubmission = SubmissionsServiceMock.Store.verifyCalledWith()
        updatedSubmission.status should matchPattern {
          case Submission.Status.Submitted(_, requestedByEmail) =>
        }
        EmailConnectorMock.verifyZeroInteractions()
        ResponsibleIndividualVerificationServiceMock.verifyZeroInteractions()
        val acceptance        = ApplicationServiceMock.AddTermsOfUseAcceptance.verifyCalledWith(application.id)
        acceptance.responsibleIndividual shouldBe responsibleIndividual
        acceptance.submissionId shouldBe answeredSubmission.id
      }

      "don't update state if production app" in new Setup {
        namingServiceReturns(ValidName)
        val prodApplication = anApplicationData(applicationId, productionState(requestedByEmail))
        ApplicationRepoMock.Save.thenReturn(prodApplication)
        StateHistoryRepoMock.Insert.thenAnswer()
        AuditServiceMock.Audit.thenReturnSuccess()
        SubmissionsServiceMock.Store.thenReturn()
        ApplicationServiceMock.AddTermsOfUseAcceptance.thenReturn(prodApplication)
        EmailConnectorMock.SendVerifyResponsibleIndividualNotification.thenReturnSuccess()
        ResponsibleIndividualVerificationServiceMock.CreateNewVerification.thenCreateNewVerification()

        val result = await(underTest.requestApproval(prodApplication, answeredSubmission, requestedByName, requestedByEmail))

        result shouldBe RequestApprovalsService.ApprovalAccepted(prodApplication)
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyCalled()
        val savedAppData = ApplicationRepoMock.Save.verifyCalled()
        savedAppData.state.name shouldBe State.PRODUCTION

        val updatedSubmission = SubmissionsServiceMock.Store.verifyCalledWith()
        updatedSubmission.status should matchPattern {
          case Submission.Status.Submitted(_, requestedByEmail) =>
        }
      }

      "return duplicate application name if duplicate" in new Setup {
        namingServiceReturns(DuplicateName)

        val result = await(underTest.requestApproval(application, answeredSubmission, requestedByName, requestedByEmail))

        val generatedName = SubmissionDataExtracter.getApplicationName(submittedSubmission).get

        result shouldBe RequestApprovalsService.ApprovalRejectedDueToDuplicateName(generatedName)
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return illegal application name if deny-listed name" in new Setup {
        namingServiceReturns(InvalidName)

        val generatedName = SubmissionDataExtracter.getApplicationName(submittedSubmission).get

        val result = await(underTest.requestApproval(application, answeredSubmission, requestedByName, requestedByEmail))

        result shouldBe RequestApprovalsService.ApprovalRejectedDueToIllegalName(generatedName)
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return incomplete for an incomplete submission" in new Setup {
        val result = await(underTest.requestApproval(application, answeringSubmission, requestedByName, requestedByEmail))

        result should matchPattern {
          case RequestApprovalsService.ApprovalRejectedDueToIncorrectSubmissionState(_) =>
        }
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return incomplete for an submitted submission" in new Setup {
        val result = await(underTest.requestApproval(application, submittedSubmission, requestedByName, requestedByEmail))

        result should matchPattern {
          case RequestApprovalsService.ApprovalRejectedDueToIncorrectSubmissionState(_) =>
        }
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return application in incorrect state an application not in TESTING" in new Setup {
        val pendingApplication: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState(requestedByEmail))

        val result = await(underTest.requestApproval(pendingApplication, answeringSubmission, requestedByName, requestedByEmail))

        result shouldBe RequestApprovalsService.ApprovalRejectedDueToIncorrectApplicationState
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }
    }
  }
}
