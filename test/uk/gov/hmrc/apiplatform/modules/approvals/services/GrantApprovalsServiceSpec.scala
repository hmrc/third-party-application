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

import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.mocks.services.TermsOfUseInvitationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.util._

class GrantApprovalsServiceSpec extends AsyncHmrcSpec {

  trait Setup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationServiceMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with SubmissionsServiceMockModule
      with EmailConnectorMockModule
      with StoredApplicationFixtures
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

    val applicationPendingGKApproval: StoredApplication = storedApp.copy(
      state = appStatePendingGatekeeperApproval,
      access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )

    val prodAppId = applicationId

    val applicationProduction: StoredApplication = storedApp.copy(
      state = appStateProduction,
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
      val result  = await(underTest.grantWithWarningsForTouUplift(storedApp.withState(appStateTesting), warningsSubmission, gatekeeperUserName, warning))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }

    "fail to grant the specified application if the submission is not in the warnings state" in new Setup {
      val warning = "Here are some warnings"
      val result  = await(underTest.grantWithWarningsForTouUplift(applicationProduction, answeredSubmission, gatekeeperUserName, warning))

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
      val result  = await(underTest.declineForTouUplift(storedApp.withState(appStateTesting), failSubmission, gatekeeperUserName, warning))

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
      val result  = await(underTest.resetForTouUplift(storedApp.withState(appStateTesting), pendingRISubmission, gatekeeperUserName, warning))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }
  }

  "GrantApprovalsService.deleteTouUplift" should {
    "delete the specified ToU submission" in new Setup {

      SubmissionsServiceMock.DeleteAll.thenReturn()
      TermsOfUseInvitationServiceMock.UpdateResetBackToEmailSent.thenReturn()
      ResponsibleIndividualVerificationRepositoryMock.DeleteAllByApplicationId.succeeds()

      val result = await(underTest.deleteTouUplift(applicationProduction, pendingRISubmission, gatekeeperUserName))

      result should matchPattern {
        case GrantApprovalsService.Actioned(app) =>
      }
      SubmissionsServiceMock.DeleteAll.verifyCalledWith(applicationProduction.id)
      TermsOfUseInvitationServiceMock.UpdateResetBackToEmailSent.verifyCalledWith(applicationProduction.id)
      ResponsibleIndividualVerificationRepositoryMock.DeleteAllByApplicationId.verifyCalledWith(applicationProduction.id)
    }

    "fail to delete the specified submission if the application is in the incorrect state" in new Setup {
      val result = await(underTest.deleteTouUplift(applicationProduction.withState(appStateTesting), pendingRISubmission, gatekeeperUserName))

      result shouldBe GrantApprovalsService.RejectedDueToIncorrectApplicationState
    }
  }
}
