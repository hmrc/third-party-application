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
import cats.data.{NonEmptyChain, NonEmptyList, Validated}
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.ChangeProductionApplicationNameCommandHandler
import uk.gov.hmrc.thirdpartyapplication.services.events.NameChangedNotificationEventHandler
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApplicationUpdateServiceSpec
  extends AsyncHmrcSpec
  with BeforeAndAfterAll
  with ApplicationStateUtil
  with ApplicationTestData
  with UpliftRequestSamples
  with FixedClock {

  trait Setup extends AuditServiceMockModule
    with ApplicationRepositoryMockModule {

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

    val mockChangeProductionApplicationNameCommandHandler: ChangeProductionApplicationNameCommandHandler = mock[ChangeProductionApplicationNameCommandHandler]
    val mockNameChangedEventHandler: NameChangedNotificationEventHandler = mock[NameChangedNotificationEventHandler]

    val underTest = new ApplicationUpdateService(
      ApplicationRepoMock.aMock,
      mockChangeProductionApplicationNameCommandHandler,
      mockNameChangedEventHandler
    )
  }

  val timestamp = LocalDateTime.now
  val gatekeeperUser = "gkuser1"

  "update with ChangeProductionApplicationName" should {

    "return the updated application if the application exists" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      val appAfter = applicationData.copy(name = newName)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)

      val nameChangedEvent = NameChanged(applicationId, timestamp, instigator, applicationData.name, appAfter.name)
      val nameChangedEmailEvent = NameChangedEmailSent(applicationId, timestamp, instigator, applicationData.name, appAfter.name, loggedInUser, Set(loggedInUser, "bob@example.com"))

      when(mockChangeProductionApplicationNameCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationName])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(nameChangedEvent, nameChangedEmailEvent)).toValidatedNec)
      )
      when(mockNameChangedEventHandler.sendAdviceEmail(*[NameChangedEmailSent])(*)).thenReturn(
        Future.successful(HasSucceeded)
      )
      val result = await(underTest.update(applicationId, changeName).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(nameChangedEvent)
      result shouldBe Right(appAfter)
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, changeName).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }

    "return error for unknown update types" in new Setup {
      val app = anApplicationData(applicationId)
      ApplicationRepoMock.Fetch.thenReturn(app)

      case class UnknownApplicationUpdate(timestamp: LocalDateTime, instigator: UserId) extends ApplicationUpdate
      val unknownUpdate = UnknownApplicationUpdate(timestamp, instigator)

      val result = await(underTest.update(applicationId, unknownUpdate).value)

      result shouldBe Left(NonEmptyChain.one(s"Unknown ApplicationUpdate type $unknownUpdate"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }

    "return error for unknown event types" in new Setup {
      val app = anApplicationData(applicationId)
      ApplicationRepoMock.Fetch.thenReturn(app)
      val appAfter = applicationData.copy(name = newName)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)

      case class UnknownEvent(applicationId: ApplicationId, timestamp: LocalDateTime, instigator: UserId, requester: String) extends UpdateApplicationNotificationEvent
      val unknownEvent = UnknownEvent(applicationId, timestamp, instigator, loggedInUser)
      val nameChangedEvent = NameChanged(applicationId, timestamp, instigator, applicationData.name, appAfter.name)
      when(mockChangeProductionApplicationNameCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationName])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(nameChangedEvent, unknownEvent)).toValidatedNec)
      )

      val ex: RuntimeException = intercept[RuntimeException](await(underTest.update(applicationId, changeName).value))

      ex.getMessage shouldBe s"UnexpectedEvent type for emailAdvice $unknownEvent"
    }

    "return error for no repository event types" in new Setup {
      val app = anApplicationData(applicationId)
      ApplicationRepoMock.Fetch.thenReturn(app)
      val appAfter = applicationData.copy(name = newName)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)

      val nameChangedEmailEvent = NameChangedEmailSent(applicationId, timestamp, instigator, applicationData.name, appAfter.name, loggedInUser, Set(loggedInUser, "bob@example.com"))
      when(mockChangeProductionApplicationNameCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationName])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.one(nameChangedEmailEvent)).toValidatedNec)
      )
      val result = await(underTest.update(applicationId, changeName).value)

      result shouldBe Left(NonEmptyChain.one(s"No repository events found for this command"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }
}
