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
import java.util.concurrent.TimeUnit

import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.{DateTime, LocalDate}
import org.mockito.ArgumentMatchersSugar
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.connector.{EmailConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled._
import unit.uk.gov.hmrc.thirdpartyapplication.helpers.StubLogger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class ApplicationToBeDeletedNotificationsSpec extends PlaySpec
  with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with FutureAwaits with DefaultAwaitTimeout {

  trait Setup {
    def jobConfiguration(sendNotificationsInAdvance: String, dryRun: Boolean): Config = {
      val emailServiceURL = "https://email.service"
      val emailTemplateId = "apiApplicationToBeDeletedNotification"
      val environmentName = "Test Environment"

      ConfigFactory.parseString(
        s"""
           | DeleteUnusedApplications {
           |  startTime = "10:00",
           |  executionInterval = "1d",
           |  enabled = true,
           |  deleteApplicationsIfUnusedFor = 365d,
           |  dryRun = false
           | },
           |
           | ApplicationToBeDeletedNotifications {
           |  sendNotificationsInAdvance = $sendNotificationsInAdvance,
           |  emailServiceURL = $emailServiceURL,
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
    val jobUnderTest =
      new ApplicationToBeDeletedNotifications(
        configuration, mockApplicationRepository, mockThirdPartyDeveloperConnector, mockEmailConnector, reactiveMongoComponent)
  }

  trait EmailNotificationSetup extends Setup {
    val configuration = new Configuration(jobConfiguration("30d", dryRun = false))
    val jobUnderTest =
      new ApplicationToBeDeletedNotifications(
        configuration, mockApplicationRepository, mockThirdPartyDeveloperConnector, mockEmailConnector, reactiveMongoComponent)
  }

  "ApplicationToBeDeletedNotifications configured to Dry Run" should {

  }

  "ApplicationToBeDeletedNotifications configured to Email" should {

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

  "DryRunNotifier" should {
    val environmentName = "Test Environment"
    val stubLogger = new StubLogger
    val notifierUnderTest = new DryRunNotifier(environmentName, stubLogger)

    "correctly redact names and email addresses" in {
      notifierUnderTest.redact("Nathan") must be ("N****n")
    }

    "correctly redact email addresses" in {
      notifierUnderTest.redactEmail("nathan@aurora.com") must be ("n****n@a********m")
    }

    "correctly show list of users" in {
      val users = Seq(("nathan@aurora.com", "Nathan", "Scott"), ("cameron@aurora.com", "Cameron", "Taylor"))
      val expectedString = "N****n S***t (n****n@a********m), C*****n T****r (c*****n@a********m)"

      notifierUnderTest.redactedUserList(users) must be (expectedString)
    }

    "log message at INFO level" in {
      val lastUsedDate = DateTime.now.minusMonths(11)
      val users = Seq(("nathan@aurora.com", "Nathan", "Scott"), ("cameron@aurora.com", "Cameron", "Taylor"))
      val notification = ApplicationToBeDeletedNotification(UUID.randomUUID, "Application Name", lastUsedDate, new LocalDate(lastUsedDate.plusDays(365)), users)

      val result: NotificationResult = await(notifierUnderTest.notifyApplicationToBeDeleted(notification))

      result must be (NotificationSent)
      stubLogger.infoMessages.size must be (1)
    }
  }

  "EmailNotifier" should {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val environmentName = "Test Environment"
    val deleteUnusedApplicationsAfter = FiniteDuration(365, TimeUnit.DAYS)
    val mockEmailConnector = mock[EmailConnector]
    val notifierUnderTest = new EmailNotifier(environmentName, deleteUnusedApplicationsAfter, mockEmailConnector)

    "return NotificationSent if email is sent" in {
      val userEmailAddress = "nathan@aurora.com"
      val userFirstName = "Nathan"
      val userLastName = "Scott"
      val applicationName = "Test Application"
      val lastUsedDate = DateTime.now.minusMonths(11)
      val deletionDate = DateTime.now.plusMonths(1).toLocalDate

      val notification =
        ApplicationToBeDeletedNotification(
          UUID.randomUUID,
          applicationName,
          lastUsedDate,
          deletionDate,
          Seq((userEmailAddress, userFirstName, userLastName)))

      when(
        mockEmailConnector.sendApplicationToBeDeletedNotification(
          eqTo(userEmailAddress),
          eqTo(userFirstName),
          eqTo(userLastName),
          eqTo(applicationName),
          eqTo(environmentName),
          eqTo(notification.lastUsedInWords),
          eqTo("365 days"),
          eqTo(notification.deletionDateInWords))(*))
        .thenReturn(Future.successful(HttpResponse(200)))

      val result = await(notifierUnderTest.notifyApplicationToBeDeleted(notification))

      result must be (NotificationSent)
    }

    "return NotificationFailed if all email sending fails" in {
      val notification =
        ApplicationToBeDeletedNotification(
          UUID.randomUUID,
          "Test Application",
          DateTime.now.minusMonths(11),
          DateTime.now.plusMonths(1).toLocalDate,
          Seq(("nathan@aurora.com", "Nathan", "Scott")))

      when(mockEmailConnector.sendApplicationToBeDeletedNotification(*, *, *, *, *, *, *, *)(*)).thenReturn(Future.successful(HttpResponse(400)))

      val result = await(notifierUnderTest.notifyApplicationToBeDeleted(notification))

      result must be (NotificationFailed)
    }

    "return NotificationSent if at least one email is sent even if others fail" in {
      val notification =
        ApplicationToBeDeletedNotification(
          UUID.randomUUID,
          "Test Application",
          DateTime.now.minusMonths(11),
          DateTime.now.plusMonths(1).toLocalDate,
          Seq(("nathan@aurora.com", "Nathan", "Scott"), ("cameron@aurora.com", "Cameron", "Taylor")))

      when(mockEmailConnector.sendApplicationToBeDeletedNotification(eqTo("nathan@aurora.com"), eqTo("Nathan"), eqTo("Scott"), *, *, *, *, *)(*))
        .thenReturn(Future.successful(HttpResponse(200)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotification(eqTo("cameron@aurora.com"), eqTo("Cameron"), eqTo("Taylor"), *, *, *, *, *)(*))
        .thenReturn(Future.successful(HttpResponse(400)))

      val result = await(notifierUnderTest.notifyApplicationToBeDeleted(notification))

      result must be (NotificationSent)
    }
  }
}
