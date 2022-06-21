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

import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.ActualAnswersAsText
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.QuestionsAndAnswersToMap
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models._

import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

class DeclineApprovalsServiceSpec extends AsyncHmrcSpec {

  trait Setup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with SubmissionsServiceMockModule
      with ApplicationTestData
      with SubmissionsTestData
      with FixedClock {

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val fmt = DateTimeFormatter.ISO_DATE_TIME

    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")
    val acceptanceDate        = LocalDateTime.now(clock)

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
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List(acceptance)
    )

    val application: ApplicationData = anApplicationData(
      applicationId,
      pendingGatekeeperApprovalState("bob@fastshow.com"),
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val underTest = new DeclineApprovalsService(
      AuditServiceMock.aMock,
      ApplicationRepoMock.aMock,
      StateHistoryRepoMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      SubmissionsServiceMock.aMock,
      clock
    )

    val responsibleIndividualVerificationDate = LocalDateTime.now(clock)
  }

  "DeclineApprovalsService" should {
    "decline the specified application" in new Setup {
      import uk.gov.hmrc.thirdpartyapplication.domain.models.State._

      ApplicationRepoMock.Save.thenReturn(application)
      StateHistoryRepoMock.Insert.thenAnswer()
      SubmissionsServiceMock.Store.thenReturn()
      ResponsibleIndividualVerificationRepositoryMock.DeleteBySubmission.thenReturnSuccess()
      AuditServiceMock.AuditGatekeeperAction.thenReturnSuccess()

      val result = await(underTest.decline(application, submittedSubmission, gatekeeperUserName, reasons))

      result shouldBe DeclineApprovalsService.Actioned(application)
      ApplicationRepoMock.Save.verifyCalled().state.name shouldBe TESTING
      val finalSubmission = SubmissionsServiceMock.Store.verifyCalledWith()
      finalSubmission.status.isAnswering shouldBe true
      finalSubmission.instances.tail.head.statusHistory.head should matchPattern {
        case Submission.Status.Declined(_, gatekeeperUserName, reasons) =>
      }

      val (someQuestionId, expectedAnswer) = submittedSubmission.latestInstance.answersToQuestions.head
      val someQuestionWording              = QuestionsAndAnswersToMap.stripSpacesAndCapitalise(submittedSubmission.findQuestion(someQuestionId).get.wording.value)

      AuditServiceMock.AuditGatekeeperAction.verifyUserName() shouldBe gatekeeperUserName
      AuditServiceMock.AuditGatekeeperAction.verifyAction() shouldBe AuditAction.ApplicationApprovalDeclined
      AuditServiceMock.AuditGatekeeperAction.verifyExtras().get("responsibleIndividual.verification.date").value shouldBe acceptanceDate.format(fmt)
      AuditServiceMock.AuditGatekeeperAction.verifyExtras().get(someQuestionWording).value shouldBe ActualAnswersAsText(expectedAnswer)
      ResponsibleIndividualVerificationRepositoryMock.DeleteBySubmission.verifyCalledWith(submittedSubmission)
    }

    "fail to decline the specified application if the application is in the incorrect state" in new Setup {
      val result = await(underTest.decline(anApplicationData(applicationId, testingState()), answeredSubmission, gatekeeperUserName, reasons))

      result shouldBe DeclineApprovalsService.RejectedDueToIncorrectApplicationState

      ResponsibleIndividualVerificationRepositoryMock.DeleteBySubmission.verifyNeverCalledWith(submittedSubmission)
    }

    "fail to decline the specified application if the submission is not in the submitted state" in new Setup {
      val result = await(underTest.decline(application, answeredSubmission, gatekeeperUserName, reasons))

      result shouldBe DeclineApprovalsService.RejectedDueToIncorrectSubmissionState

      ResponsibleIndividualVerificationRepositoryMock.DeleteBySubmission.verifyNeverCalledWith(submittedSubmission)
    }
  }
}
