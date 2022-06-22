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

package uk.gov.hmrc.thirdpartyapplication.services

import akka.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.events.NameChangedNotificationEventHandler
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NotificationServiceSpec
  extends AsyncHmrcSpec
  with BeforeAndAfterAll
  with ApplicationStateUtil
  with ApplicationTestData
  with UpliftRequestSamples
  with FixedClock {

  trait Setup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val actorSystem: ActorSystem = ActorSystem("System")

    val applicationId = ApplicationId.random
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")
    val testImportantSubmissionData = ImportantSubmissionData(Some("organisationUrl.com"),
                              responsibleIndividual,
                              Set(ServerLocation.InUK),
                              TermsAndConditionsLocation.InDesktopSoftware,
                              PrivacyPolicyLocation.InDesktopSoftware,
                              List.empty)
    val applicationData: ApplicationData = anApplicationData(
                              applicationId,
                              access = Standard(importantSubmissionData = Some(testImportantSubmissionData)))

    val instigator = applicationData.collaborators.head.userId
    val newName = "robs new app"
    val changeName = ChangeProductionApplicationName(instigator, timestamp, gatekeeperUser, newName)

    lazy val locked = false
    protected val mockitoTimeout = 1000
    val response = mock[HttpResponse]

    val mockNameChangedNotificationEventHandler: NameChangedNotificationEventHandler = mock[NameChangedNotificationEventHandler]

    val underTest = new NotificationService(
      mockNameChangedNotificationEventHandler
    )
  }

  val timestamp = LocalDateTime.now
  val gatekeeperUser = "gkuser1"

  "sendNotifications" should {

    "call the event handler and return successfully" in new Setup {
      val nameChangedEmailEvent = NameChangedEmailSent(applicationId, timestamp, instigator, "oldName", newName, loggedInUser)

      when(mockNameChangedNotificationEventHandler.sendAdviceEmail(*[ApplicationData], *)(*)).thenReturn(
        Future.successful(HasSucceeded)
      )
      
      val result = await(underTest.sendNotifications(applicationData, List(nameChangedEmailEvent)))
      result shouldBe List(HasSucceeded)
    }

    "return error for unknown update types" in new Setup {
      case class UnknownApplicationUpdateEvent(applicationId: ApplicationId, timestamp: LocalDateTime, instigator: UserId, requester: String) extends UpdateApplicationNotificationEvent
      val unknownEvent = UnknownApplicationUpdateEvent(applicationId, timestamp, instigator, loggedInUser)

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.sendNotifications(applicationData, List(unknownEvent))))
      ex.getMessage shouldBe s"UnexpectedEvent type for sendNotification $unknownEvent"
    }
  }
}
