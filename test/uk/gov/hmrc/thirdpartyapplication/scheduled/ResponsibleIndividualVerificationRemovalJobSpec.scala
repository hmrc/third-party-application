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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import java.time.temporal.ChronoUnit.SECONDS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, HOURS, MINUTES}

import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ImportantSubmissionData, PrivacyPolicyLocations, ResponsibleIndividual, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.REMINDERS_SENT
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualToUVerification, ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeclineResponsibleIndividualDidNotVerify
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationCommandDispatcherMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ResponsibleIndividualVerificationRemovalJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil
    with ApplicationTestData {

  trait Setup extends ResponsibleIndividualVerificationRepositoryMockModule with ApplicationCommandDispatcherMockModule
      with SubmissionsTestData {

    val mockLockKeeper = mock[ResponsibleIndividualVerificationRemovalJobLockService]

    val riName         = "bob responsible"
    val riEmail        = "bob.responsible@example.com"
    val appName        = "my app"
    val requesterName  = "bob requester"
    val requesterEmail = "bob.requester@example.com"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app             = anApplicationData(
      ApplicationId.random,
      access = Access.Standard(importantSubmissionData = Some(importantSubmissionData)),
      state = ApplicationStateExamples.pendingResponsibleIndividualVerification(requesterEmail, requesterName)
    ).copy(name = appName)
    val initialDelay    = FiniteDuration(1, MINUTES)
    val interval        = FiniteDuration(1, HOURS)
    val removalInterval = FiniteDuration(20, DAYS)
    val jobConfig       = ResponsibleIndividualVerificationRemovalJobConfig(initialDelay, interval, removalInterval, true)

    val job = new ResponsibleIndividualVerificationRemovalJob(
      mockLockKeeper,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      ApplicationCommandDispatcherMock.aMock,
      clock,
      jobConfig
    )
  }

  "ResponsibleIndividualVerificationRemovalJob" should {
    "send email and remove database record" in new Setup {
      ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccess(app)

      val code         = "123242423432432432"
      val verification =
        ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId(code), app.id, completelyAnswerExtendedSubmission.submission.id, 0, app.name, now)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.thenReturn(verification)

      await(job.runJob)

      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.verifyCalledWith(
        ResponsibleIndividualVerification.VerificationTypeToU,
        REMINDERS_SENT,
        now.minus(removalInterval.toSeconds, SECONDS)
      )
      val command                                  = ApplicationCommandDispatcherMock.Dispatch.verifyCalledWith(app.id)
      val declineResponsibleIndividualDidNotVerify = command.asInstanceOf[DeclineResponsibleIndividualDidNotVerify]
      declineResponsibleIndividualDidNotVerify.code shouldBe code
    }

    "continue after invalid records" in new Setup {
      val badApp = app.copy(id = ApplicationId.random)
      ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccess(app)
      ApplicationCommandDispatcherMock.Dispatch.thenReturnFailedFor(badApp.id)

      val code1         = "123242423432432432"
      val verification1 =
        ResponsibleIndividualToUVerification(
          ResponsibleIndividualVerificationId(code1),
          badApp.id,
          completelyAnswerExtendedSubmission.submission.id,
          0,
          badApp.name,
          now
        )
      val code2         = "725446087565645698"
      val verification2 =
        ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId(code2), app.id, completelyAnswerExtendedSubmission.submission.id, 0, app.name, now)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.thenReturn(verification1, verification2)

      await(job.runJob)

      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.verifyCalledWith(
        ResponsibleIndividualVerification.VerificationTypeToU,
        REMINDERS_SENT,
        now.minus(removalInterval.toSeconds, SECONDS)
      )
      val command                                  = ApplicationCommandDispatcherMock.Dispatch.verifyCalledWith(app.id)
      val declineResponsibleIndividualDidNotVerify = command.asInstanceOf[DeclineResponsibleIndividualDidNotVerify]
      declineResponsibleIndividualDidNotVerify.code shouldBe code2
    }
  }
}
