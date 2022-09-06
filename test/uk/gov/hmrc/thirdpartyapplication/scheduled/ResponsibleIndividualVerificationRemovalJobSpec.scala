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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.REMINDERS_SENT
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualToUVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.approvals.mocks.DeclineApprovalsServiceMockModule
import uk.gov.hmrc.apiplatform.modules.approvals.services.DeclineApprovalsService.Actioned
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import java.time.temporal.ChronoUnit.SECONDS
import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, HOURS, MINUTES}

class ResponsibleIndividualVerificationRemovalJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup extends ApplicationServiceMockModule with ApplicationRepositoryMockModule with SubmissionsServiceMockModule
      with EmailConnectorMockModule with ResponsibleIndividualVerificationRepositoryMockModule with DeclineApprovalsServiceMockModule
      with SubmissionsTestData {

    val mockLockKeeper = mock[ResponsibleIndividualVerificationRemovalJobLockService]
    val timeNow        = LocalDateTime.now
    val fixedClock     = Clock.fixed(timeNow.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    val riName         = "bob responsible"
    val riEmail        = "bob.responsible@example.com"
    val appName        = "my app"
    val requesterName  = "bob requester"
    val requesterEmail = "bob.requester@example.com"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail),
      Set.empty,
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app             = anApplicationData(
      ApplicationId.random,
      access = Standard(importantSubmissionData = Some(importantSubmissionData)),
      state = ApplicationState().toPendingResponsibleIndividualVerification(requesterEmail, requesterName, fixedClock)
    ).copy(name = appName)
    val initialDelay    = FiniteDuration(1, MINUTES)
    val interval        = FiniteDuration(1, HOURS)
    val removalInterval = FiniteDuration(20, DAYS)
    val jobConfig       = ResponsibleIndividualVerificationRemovalJobConfig(initialDelay, interval, removalInterval, true)

    val job = new ResponsibleIndividualVerificationRemovalJob(
      mockLockKeeper,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      SubmissionsServiceMock.aMock,
      EmailConnectorMock.aMock,
      ApplicationRepoMock.aMock,
      DeclineApprovalsServiceMock.aMock,
      fixedClock,
      jobConfig
    )
  }

  "ResponsibleIndividualVerificationRemovalJob" should {
    "send email and remove database record" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(app)
      SubmissionsServiceMock.Fetch.thenReturn(completelyAnswerExtendedSubmission)
      DeclineApprovalsServiceMock.Decline.thenReturn(Actioned(app))
      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.DeleteById.thenReturnSuccess()

      val verification =
        ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId.random, app.id, completelyAnswerExtendedSubmission.submission.id, 0, app.name, LocalDateTime.now)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.thenReturn(verification)
      ResponsibleIndividualVerificationRepositoryMock.DeleteById.thenReturnSuccess()

      await(job.runJob)

      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.verifyCalledWith(riName, requesterEmail, appName, requesterName)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.verifyCalledWith(ResponsibleIndividualVerification.VerificationTypeToU, REMINDERS_SENT, timeNow.minus(removalInterval.toSeconds, SECONDS))
      ResponsibleIndividualVerificationRepositoryMock.DeleteById.verifyCalledWith(verification.id)
      DeclineApprovalsServiceMock.Decline.verifyCalledWith(
        app,
        completelyAnswerExtendedSubmission.submission,
        riEmail,
        "The responsible individual did not accept the terms of use in 20 days."
      )
    }

    "continue after invalid records" in new Setup {
      val badApp = app.copy(id = ApplicationId.random)
      ApplicationRepoMock.Fetch.thenReturn(app)
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(badApp.id)
      SubmissionsServiceMock.Fetch.thenReturn(completelyAnswerExtendedSubmission)
      DeclineApprovalsServiceMock.Decline.thenReturn(Actioned(app))
      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.DeleteById.thenReturnSuccess()

      val verification1 =
        ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId.random, badApp.id, completelyAnswerExtendedSubmission.submission.id, 0, badApp.name, LocalDateTime.now)
      val verification2 =
        ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId.random, app.id, completelyAnswerExtendedSubmission.submission.id, 0, app.name, LocalDateTime.now)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.thenReturn(verification1, verification2)
      ResponsibleIndividualVerificationRepositoryMock.DeleteById.thenReturnSuccess()

      await(job.runJob)

      EmailConnectorMock.SendResponsibleIndividualDidNotVerify.verifyCalledWith(riName, requesterEmail, appName, requesterName)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.verifyCalledWith(ResponsibleIndividualVerification.VerificationTypeToU, REMINDERS_SENT, timeNow.minus(removalInterval.toSeconds, SECONDS))
      ResponsibleIndividualVerificationRepositoryMock.DeleteById.verifyNeverCalledWith(verification1.id)
      ResponsibleIndividualVerificationRepositoryMock.DeleteById.verifyCalledWith(verification2.id)
      DeclineApprovalsServiceMock.Decline.verifyCalledWith(
        app,
        completelyAnswerExtendedSubmission.submission,
        riEmail,
        "The responsible individual did not accept the terms of use in 20 days."
      )
    }
  }
}
