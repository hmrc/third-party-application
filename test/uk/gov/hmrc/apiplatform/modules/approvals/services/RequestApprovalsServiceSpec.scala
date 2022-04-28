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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, Standard}

import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.models.ValidName
import uk.gov.hmrc.thirdpartyapplication.models.DuplicateName
import uk.gov.hmrc.thirdpartyapplication.models.InvalidName
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationNameValidationResult
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{NoAnswer, Question, SingleChoiceAnswer, Submission, TextAnswer}

class RequestApprovalsServiceSpec extends AsyncHmrcSpec {

  trait Setup
    extends AuditServiceMockModule
    with ApplicationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with SubmissionsServiceMockModule
    with SubmissionsTestData
    with ApplicationTestData {

    val requestedByEmailAddress = "email@example.com"
    val requestedByName = "bob example"
    val application: ApplicationData = anApplicationData(applicationId, testingState())

    val mockApprovalsNamingService = mock[ApprovalsNamingService]

    def namingServiceReturns(result: ApplicationNameValidationResult) =
      when(mockApprovalsNamingService.validateApplicationNameAndAudit(*, *[ApplicationId], *)(*)).thenReturn(successful(result))

    def getAnswerToQuestion(questionId: Question.Id): String = answersToQuestions.get(questionId) match {
      case Some(TextAnswer(answer)) => answer
    }

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val underTest = new RequestApprovalsService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, mockApprovalsNamingService, SubmissionsServiceMock.aMock)
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

        val result = await(underTest.requestApproval(application, answeredSubmission, requestedByName, requestedByEmailAddress))

        result shouldBe RequestApprovalsService.ApprovalAccepted(fakeSavedApplication)
        StateHistoryRepoMock.Insert.verifyCalled()
        AuditServiceMock.Audit.verifyCalled()
        val savedAppData = ApplicationRepoMock.Save.verifyCalled()
        val responsibleIndividual = savedAppData.access.asInstanceOf[Standard].importantSubmissionData.get.responsibleIndividual
        responsibleIndividual.fullName.value shouldBe getAnswerToQuestion(testQuestionIdsOfInterest.responsibleIndividualNameId)
        responsibleIndividual.emailAddress.value shouldBe getAnswerToQuestion(testQuestionIdsOfInterest.responsibleIndividualEmailId)

        val updatedSubmission = SubmissionsServiceMock.Store.verifyCalledWith()
        updatedSubmission.status should matchPattern {
          case Submission.Status.Submitted(_, requestedByEmailAddress) =>
        }
      }

      "update state, save and audit with RI details not in questionnaire answers" in new Setup {
        namingServiceReturns(ValidName)
        val fakeSavedApplication = application.copy(normalisedName = "somethingElse")
        ApplicationRepoMock.Save.thenReturn(fakeSavedApplication)
        StateHistoryRepoMock.Insert.thenAnswer()
        AuditServiceMock.Audit.thenReturnSuccess()
        SubmissionsServiceMock.Store.thenReturn()
        val answersWithoutRIDetails = answersToQuestions
          .updated(testQuestionIdsOfInterest.responsibleIndividualIsRequesterId, SingleChoiceAnswer("Yes"))
          .updated(testQuestionIdsOfInterest.responsibleIndividualEmailId, NoAnswer)
          .updated(testQuestionIdsOfInterest.responsibleIndividualNameId, NoAnswer)
        val answeredSubmissionWithoutRIDetails = answeredSubmission.hasCompletelyAnsweredWith(answersWithoutRIDetails)

        val result = await(underTest.requestApproval(application, answeredSubmissionWithoutRIDetails, requestedByName, requestedByEmailAddress))

        result shouldBe RequestApprovalsService.ApprovalAccepted(fakeSavedApplication)
        StateHistoryRepoMock.Insert.verifyCalled()
        AuditServiceMock.Audit.verifyCalled()
        val savedAppData = ApplicationRepoMock.Save.verifyCalled()
        val responsibleIndividual = savedAppData.access.asInstanceOf[Standard].importantSubmissionData.get.responsibleIndividual
        responsibleIndividual.fullName.value shouldBe requestedByName
        responsibleIndividual.emailAddress.value shouldBe requestedByEmailAddress

        val updatedSubmission = SubmissionsServiceMock.Store.verifyCalledWith()
        updatedSubmission.status should matchPattern {
          case Submission.Status.Submitted(_, requestedByEmailAddress) =>
        }
      }

      "return duplicate application name if duplicate" in new Setup {
        namingServiceReturns(DuplicateName)

        val result = await(underTest.requestApproval(application, answeredSubmission, requestedByName, requestedByEmailAddress))

        result shouldBe RequestApprovalsService.ApprovalRejectedDueToDuplicateName(expectedAppName)
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return illegal application name if deny-listed name" in new Setup {
        namingServiceReturns(InvalidName)

        val result = await(underTest.requestApproval(application, answeredSubmission, requestedByName, requestedByEmailAddress))

        result shouldBe RequestApprovalsService.ApprovalRejectedDueToIllegalName(expectedAppName)
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return incomplete for an incomplete submission" in new Setup {
        val result = await(underTest.requestApproval(application, answeringSubmission, requestedByName, requestedByEmailAddress))

        result should matchPattern {
          case RequestApprovalsService.ApprovalRejectedDueToIncorrectSubmissionState(_) =>
        }
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return incomplete for an submitted submission" in new Setup {
        val result = await(underTest.requestApproval(application, submittedSubmission, requestedByName, requestedByEmailAddress))

        result should matchPattern {
          case RequestApprovalsService.ApprovalRejectedDueToIncorrectSubmissionState(_) =>
        }
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }

      "return application in incorrect state an application not in TESTING" in new Setup {
        val prodApplication: ApplicationData = anApplicationData(applicationId, productionState(requestedByEmailAddress))
        
        val result = await(underTest.requestApproval(prodApplication, answeringSubmission, requestedByName, requestedByEmailAddress))

        result shouldBe RequestApprovalsService.ApprovalRejectedDueToIncorrectApplicationState
        StateHistoryRepoMock.Insert.verifyNeverCalled()
        AuditServiceMock.Audit.verifyNeverCalled()
        ApplicationRepoMock.Save.verifyNeverCalled()
      }
    }
  }
}