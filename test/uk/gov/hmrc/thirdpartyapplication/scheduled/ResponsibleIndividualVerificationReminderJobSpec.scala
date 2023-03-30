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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, PrivacyPolicyLocations, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{INITIAL, REMINDERS_SENT}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualToUVerification, ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

class ResponsibleIndividualVerificationReminderJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil with FixedClock {

  trait Setup extends ApplicationServiceMockModule with EmailConnectorMockModule with ResponsibleIndividualVerificationRepositoryMockModule {

    val mockLockKeeper = mock[ResponsibleIndividualVerificationReminderJobLockService]
    val mockRepo       = ResponsibleIndividualVerificationRepositoryMock.aMock

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

    val app              = anApplicationData(
      ApplicationId.random,
      access = Standard(importantSubmissionData = Some(importantSubmissionData)),
      state = ApplicationState().toPendingResponsibleIndividualVerification(requesterEmail, requesterName, clock)
    ).copy(name = appName)
    val initialDelay     = FiniteDuration(1, MINUTES)
    val interval         = FiniteDuration(1, HOURS)
    val reminderInterval = FiniteDuration(10, DAYS)
    val jobConfig        = ResponsibleIndividualVerificationReminderJobConfig(initialDelay, interval, reminderInterval, true)
    val job              = new ResponsibleIndividualVerificationReminderJob(mockLockKeeper, mockRepo, EmailConnectorMock.aMock, ApplicationServiceMock.aMock, clock, jobConfig)
  }

  "ResponsibleIndividualVerificationReminderJob" should {
    "send emails correctly and update state of database record" in new Setup {
      ApplicationServiceMock.Fetch.thenReturn(app)
      EmailConnectorMock.SendVerifyResponsibleIndividualNotification.thenReturnSuccess()
      EmailConnectorMock.SendVerifyResponsibleIndividualReminderToAdmin.thenReturnSuccess()

      val verification = ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId.random, ApplicationId.random, SubmissionId.random, 0, appName, now)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.thenReturn(verification)
      ResponsibleIndividualVerificationRepositoryMock.UpdateState.thenReturnSuccess()

      await(job.runJob)

      EmailConnectorMock.SendVerifyResponsibleIndividualNotification.verifyCalledWith(riName, riEmail.toLaxEmail, appName, requesterName, verification.id.value)
      EmailConnectorMock.SendVerifyResponsibleIndividualReminderToAdmin.verifyCalledWith(riName, requesterEmail.toLaxEmail, appName, requesterName)
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.verifyCalledWith(
        ResponsibleIndividualVerification.VerificationTypeToU,
        INITIAL,
        now.minus(reminderInterval.toSeconds, SECONDS)
      )
      ResponsibleIndividualVerificationRepositoryMock.UpdateState.verifyCalledWith(verification.id, REMINDERS_SENT)
    }
  }
}
