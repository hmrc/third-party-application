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

import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.INITIAL
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualUpdateVerification, ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.mocks.ApplicationUpdateServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.temporal.ChronoUnit.SECONDS
import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, HOURS, MINUTES}

class ResponsibleIndividualUpdateVerificationRemovalJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil
  with ApplicationTestData {

  trait Setup extends ApplicationUpdateServiceMockModule with ResponsibleIndividualVerificationRepositoryMockModule
      with SubmissionsTestData {

    val mockLockKeeper = mock[ResponsibleIndividualUpdateVerificationRemovalJobLockService]
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
      state = ApplicationState().toPendingGatekeeperApproval(requesterEmail, requesterName, fixedClock)
    ).copy(name = appName)
    val initialDelay    = FiniteDuration(1, MINUTES)
    val interval        = FiniteDuration(1, HOURS)
    val removalInterval = FiniteDuration(20, DAYS)
    val jobConfig       = ResponsibleIndividualUpdateVerificationRemovalJobConfig(initialDelay, interval, removalInterval, true)

    val job = new ResponsibleIndividualUpdateVerificationRemovalJob(
      mockLockKeeper,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      ApplicationUpdateServiceMock.aMock,
      fixedClock,
      jobConfig
    )
  }

  "ResponsibleIndividualUpdateVerificationRemovalJob" should {
    "remove database record" in new Setup {
      ApplicationUpdateServiceMock.Update.thenReturnSuccess(app)

      val code = "123242423432432432"
      val verification = ResponsibleIndividualUpdateVerification(
        ResponsibleIndividualVerificationId(code), app.id, completelyAnswerExtendedSubmission.submission.id, 0, "my app", LocalDateTime.now,
        ResponsibleIndividual.build("ri name", "ri@example.com"), "Mr Admin", "admin@example.com"
      )
      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.thenReturn(verification)

      await(job.runJob)

      ResponsibleIndividualVerificationRepositoryMock.FetchByTypeStateAndAge.verifyCalledWith(ResponsibleIndividualVerification.VerificationTypeUpdate, INITIAL, timeNow.minus(removalInterval.toSeconds, SECONDS))
      val applicationUpdate = ApplicationUpdateServiceMock.Update.verifyCalledWith(app.id)
      val declineResponsibleIndividualDidNotVerify = applicationUpdate.asInstanceOf[DeclineResponsibleIndividualDidNotVerify]
      declineResponsibleIndividualDidNotVerify.code shouldBe code
    }
  }
}
