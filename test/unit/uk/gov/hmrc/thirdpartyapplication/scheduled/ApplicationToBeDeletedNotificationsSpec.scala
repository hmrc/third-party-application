/*
 * Copyright 2020 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.UUID

import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.{DateTime, LocalDate}
import org.mockito.ArgumentMatchersSugar
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import play.api.{Configuration, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.connector.{EmailConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.thirdpartyapplication.models.UserResponse
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled._
import unit.uk.gov.hmrc.thirdpartyapplication.helpers.StubLogger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApplicationToBeDeletedNotificationsSpec extends PlaySpec
  with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with FutureAwaits with DefaultAwaitTimeout {

  trait Setup {
    val environmentName = "Test Environment"

    def jobConfiguration(sendNotificationsInAdvance: String, dryRun: Boolean): Config = {
      val emailServiceURL = "https://email.service"
      val emailTemplateId = "apiApplicationToBeDeletedNotification"

      ConfigFactory.parseString(
        s"""
           | DeleteUnusedApplications {
           |  startTime = "10:00",
           |  executionInterval = 1d,
           |  enabled = true,
           |  deleteApplicationsIfUnusedFor = 365d,
           |  dryRun = false
           | }
           |
           | ApplicationToBeDeletedNotifications {
           |  startTime = "09:00",
           |  executionInterval = 1d,
           |  enabled = true,
           |  sendNotificationsInAdvance = $sendNotificationsInAdvance,
           |  emailServiceURL = "$emailServiceURL",
           |  emailTemplateId = $emailTemplateId,
           |  environmentName = $environmentName,
           |  dryRun = $dryRun
           | }
           |""".stripMargin)
    }

    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector = mock[ThirdPartyDeveloperConnector]
    val mockEmailConnector: EmailConnector = mock[EmailConnector]
    val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }
  }

  trait DryRunSetup extends Setup {
    val configuration = new Configuration(jobConfiguration("30d", dryRun = true))
    val stubLogger = new StubLogger

    val jobUnderTest: ApplicationToBeDeletedNotifications =
      new ApplicationToBeDeletedNotifications(
        configuration, mockApplicationRepository, mockThirdPartyDeveloperConnector, mockEmailConnector, reactiveMongoComponent) {
        override val logger: LoggerLike = stubLogger
      }
  }

  trait EmailNotificationSetup extends Setup {
    val configuration = new Configuration(jobConfiguration("30d", dryRun = false))
    val jobUnderTest =
      new ApplicationToBeDeletedNotifications(
        configuration, mockApplicationRepository, mockThirdPartyDeveloperConnector, mockEmailConnector, reactiveMongoComponent)
  }


  "ApplicationToBeDeletedNotification" should {
    val notification =
      ApplicationToBeDeletedNotification(
        UUID.randomUUID,
        "Application Name",
        DateTime.now.minusDays(100), // scalastyle:off magic.number
        new LocalDate(2021, 2, 5),// scalastyle:off magic.number
        Seq(("nathan@aurora.com", "Nathan", "Scott")))

    "correctly display number of days since last use" in {
      notification.lastUsedInWords must be ("100 days")
    }

    "correctly display date of deletion" in {
      notification.deletionDateInWords must be ("5 February 2021")
    }
  }

  "Job configured for dry run" should {
    val applicationId: UUID = UUID.randomUUID
    val applicationName: String = "Test Application"
    val lastUsedDate: DateTime = DateTime.now.minusMonths(11)
    val userEmailAddresses: Set[String] = Set("nathan@aurora.com")
    val userResponses: Seq[UserResponse] = Seq(UserResponse("nathan@aurora.com", "Nathan", "Scott", DateTime.now, DateTime.now, validated = true))

    "write notification to log at INFO level" in new DryRunSetup {
      implicit val hc: HeaderCarrier = HeaderCarrier()

      when(mockApplicationRepository.applicationsRequiringDeletionPendingNotification(any[DateTime]))
        .thenReturn(Future.successful(Seq((applicationId, applicationName, lastUsedDate, userEmailAddresses))))
      when(mockThirdPartyDeveloperConnector.fetchUsersByEmailAddresses(eqTo(userEmailAddresses))(*)).thenReturn(Future.successful(userResponses))

      val result = await(jobUnderTest.functionToExecute())

      stubLogger.infoMessages.size must be (1)
      verifyNoInteractions(mockEmailConnector)
    }
  }

  "Job configured for email" should {
    val applicationId: UUID = UUID.randomUUID
    val applicationName: String = "Test Application"
    val lastUsedDate: DateTime = DateTime.now.minusMonths(11)
    val userEmailAddresses: Set[String] = Set("nathan@aurora.com")
    val userResponses: Seq[UserResponse] = Seq(UserResponse("nathan@aurora.com", "Nathan", "Scott", DateTime.now, DateTime.now, validated = true))

    "send notifications via EmailConnector" in new EmailNotificationSetup {
      implicit val hc: HeaderCarrier = HeaderCarrier()

      when(mockApplicationRepository.applicationsRequiringDeletionPendingNotification(any[DateTime]))
        .thenReturn(Future.successful(Seq((applicationId, applicationName, lastUsedDate, userEmailAddresses))))
      when(mockThirdPartyDeveloperConnector.fetchUsersByEmailAddresses(eqTo(userEmailAddresses))(*)).thenReturn(Future.successful(userResponses))
      when(mockEmailConnector.sendApplicationToBeDeletedNotification(eqTo("nathan@aurora.com"), eqTo("Nathan"), eqTo("Scott"), eqTo(applicationName), eqTo(environmentName), *, *, *)(*))
        .thenReturn(Future.successful(HttpResponse(200)))
      when(mockApplicationRepository.recordDeleteNotificationSent(applicationId)).thenReturn(Future.successful(mock[ApplicationData]))

      await(jobUnderTest.functionToExecute()) must be (jobUnderTest.RunningOfJobSuccessful)
    }
  }

  "writeNotificationsToLog" should {
    "correctly redact names and email addresses" in new DryRunSetup {
      val lastUsedDate: DateTime = DateTime.now.minusMonths(11)
      val users = Seq(("nathan@aurora.com", "Nathan", "Scott"))
      val notification: ApplicationToBeDeletedNotification =
        ApplicationToBeDeletedNotification(UUID.randomUUID, "Application Name", lastUsedDate, new LocalDate(lastUsedDate.plusDays(365)), users)

      val result: (UUID, NotificationResult) = await(jobUnderTest.writeNotificationsToLog(notification))

      stubLogger.infoMessages.size must be (1)
      val logMessage: String = stubLogger.infoMessages.head
      logMessage.contains("nathan@aurora.com") must be (false)
      logMessage.contains("Nathan") must be (false)
      logMessage.contains("Scott") must be (false)
      logMessage.contains("n****n@a********m") must be (true)
      logMessage.contains("N****n") must be (true)
      logMessage.contains("S***t") must be (true)
    }

    "log message at INFO level" in new DryRunSetup {
      val applicationId: UUID = UUID.randomUUID
      val lastUsedDate: DateTime = DateTime.now.minusMonths(11)
      val users = Seq(("nathan@aurora.com", "Nathan", "Scott"), ("cameron@aurora.com", "Cameron", "Taylor"))
      val notification: ApplicationToBeDeletedNotification =
        ApplicationToBeDeletedNotification(applicationId, "Application Name", lastUsedDate, new LocalDate(lastUsedDate.plusDays(365)), users)

      val result: (UUID, NotificationResult) = await(jobUnderTest.writeNotificationsToLog(notification))

      result._1 must be (applicationId)
      result._2 must be (NotificationSent)
      stubLogger.infoMessages.size must be (1)
    }
  }

}
