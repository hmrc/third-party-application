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

import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, HOURS, MINUTES}

import org.scalatest.BeforeAndAfterAll

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, NotificationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.db.{NotificationStatus, NotificationType}
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ProductionCredentialsRequestExpiryWarningJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup extends ApplicationRepositoryMockModule with EmailConnectorMockModule with NotificationRepositoryMockModule with ApplicationTestData {

    val mockLockKeeper = mock[ProductionCredentialsRequestExpiryWarningJobLockService]
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
    val warningInterval = FiniteDuration(10, DAYS)
    val jobConfig       = ProductionCredentialsRequestExpiryWarningJobConfig(initialDelay, interval, true, warningInterval)

    val job        =
      new ProductionCredentialsRequestExpiryWarningJob(mockLockKeeper, ApplicationRepoMock.aMock, NotificationRepositoryMock.aMock, EmailConnectorMock.aMock, fixedClock, jobConfig)
    val recipients = app.collaborators.map(_.emailAddress)
  }

  "ProductionCredentialsRequestExpiryWarningJob" should {
    "send emails correctly and create a notification record" in new Setup {
      ApplicationRepoMock.FetchByStatusDetailsAndEnvironmentNotAleadyNotified.thenReturn(app)
      EmailConnectorMock.SendProductionCredentialsRequestExpiryWarning.thenReturnSuccess()
      NotificationRepositoryMock.CreateEntity.thenReturnSuccess()

      await(job.runJob)

      EmailConnectorMock.SendProductionCredentialsRequestExpiryWarning.verifyCalledWith(appName, recipients)
      val notification = NotificationRepositoryMock.CreateEntity.verifyCalledWith()
      notification.applicationId shouldBe app.id
      notification.notificationType shouldBe NotificationType.PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING
      notification.status shouldBe NotificationStatus.SENT
    }
  }
}
