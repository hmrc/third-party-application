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
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.{ChangeProductionApplicationNameCommandHandler, ChangeProductionApplicationPrivacyPolicyLocationCommandHandler, ChangeProductionApplicationTermsAndConditionsLocationCommandHandler, ChangeResponsibleIndividualToSelfCommandHandler, ChangeResponsibleIndividualToOtherCommandHandler, VerifyResponsibleIndividualCommandHandler}
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.StateHistoryRepositoryMockModule

class ApplicationUpdateServiceSpec
    extends AsyncHmrcSpec
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with ApplicationTestData
    with UpliftRequestSamples
    with FixedClock {

  trait Setup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with NotificationServiceMockModule
      with ApiPlatformEventServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val actorSystem: ActorSystem = ActorSystem("System")

    lazy val locked              = false
    protected val mockitoTimeout = 1000
    val response                 = mock[HttpResponse]

    val mockChangeProductionApplicationNameCommandHandler: ChangeProductionApplicationNameCommandHandler = mock[ChangeProductionApplicationNameCommandHandler]
    val mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler = mock[ChangeProductionApplicationPrivacyPolicyLocationCommandHandler]
    val mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler = mock[ChangeProductionApplicationTermsAndConditionsLocationCommandHandler]
    val mockChangeResponsibleIndividualToSelfCommandHandler: ChangeResponsibleIndividualToSelfCommandHandler = mock[ChangeResponsibleIndividualToSelfCommandHandler]
    val mockChangeResponsibleIndividualToOtherCommandHandler: ChangeResponsibleIndividualToOtherCommandHandler = mock[ChangeResponsibleIndividualToOtherCommandHandler]
    val mockVerifyResponsibleIndividualCommandHandler: VerifyResponsibleIndividualCommandHandler = mock[VerifyResponsibleIndividualCommandHandler]

    val underTest = new ApplicationUpdateService(
      ApplicationRepoMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      StateHistoryRepoMock.aMock,
      mockChangeProductionApplicationNameCommandHandler,
      mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
      mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
      mockChangeResponsibleIndividualToSelfCommandHandler,
      mockChangeResponsibleIndividualToOtherCommandHandler,
      mockVerifyResponsibleIndividualCommandHandler,
      NotificationServiceMock.aMock,
      ApiPlatformEventServiceMock.aMock
    )
  }

  val timestamp      = LocalDateTime.now
  val gatekeeperUser = "gkuser1"
  val adminEmail = "admin@example.com"
  val applicationId         = ApplicationId.random
  val submissionId          = Submission.Id.random
  val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

  val testImportantSubmissionData = ImportantSubmissionData(
    Some("organisationUrl.com"),
    responsibleIndividual,
    Set(ServerLocation.InUK),
    TermsAndConditionsLocation.InDesktopSoftware,
    PrivacyPolicyLocation.InDesktopSoftware,
    List.empty
  )

  val applicationData: ApplicationData = anApplicationData(
    applicationId,
    access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
  )
  val riVerification = models.ResponsibleIndividualUpdateVerification(
    ResponsibleIndividualVerificationId.random, applicationId, submissionId, 1, applicationData.name, timestamp, responsibleIndividual)
  val instigator = applicationData.collaborators.head.userId

  "update with ChangeProductionApplicationName" should {
    val newName    = "robs new app"
    val changeName = ChangeProductionApplicationName(instigator, timestamp, gatekeeperUser, newName)
    val event = ProductionAppNameChanged(
      UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), applicationData.name, newName, adminEmail)

    "return the updated application if the application exists" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      val appAfter = applicationData.copy(name = newName)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ApiPlatformEventServiceMock.ApplyEvents.succeeds

      when(mockChangeProductionApplicationNameCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationName])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )
      NotificationServiceMock.SendNotifications.thenReturnSuccess()

      val result = await(underTest.update(applicationId, changeName).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      result shouldBe Right(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.verifyCalledWith(NonEmptyList.one(event))
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
  }

  "update with ChangeProductionApplicationPrivacyPolicyLocation" should {
    val oldLocation           = PrivacyPolicyLocation.InDesktopSoftware
    val newLocation           = PrivacyPolicyLocation.Url("http://example.com")
    val changePrivacyPolicyLocation = ChangeProductionApplicationPrivacyPolicyLocation(instigator, timestamp, newLocation)
    val event                       = ProductionAppPrivacyPolicyLocationChanged(UpdateApplicationEvent.Id.random, applicationId, timestamp, CollaboratorActor(adminEmail), oldLocation, newLocation, adminEmail)

    def setPrivacyPolicyLocation(app: ApplicationData, location: PrivacyPolicyLocation) = {
      app.access match {
        case Standard(_, _, _, _, _, Some(importantSubmissionData)) => app.copy(access = app.access.asInstanceOf[Standard].copy(importantSubmissionData = Some(importantSubmissionData.copy(privacyPolicyLocation = location))))
        case _ => fail("Unexpected access type: " + app.access)
      }
    }

    "return the updated application if the application exists" in new Setup {
      val appBefore = setPrivacyPolicyLocation(applicationData, oldLocation)
      val appAfter = setPrivacyPolicyLocation(applicationData, newLocation)
      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()

      when(mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationPrivacyPolicyLocation])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, changePrivacyPolicyLocation).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      result shouldBe Right(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.verifyCalledWith(NonEmptyList.one(event))
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, changePrivacyPolicyLocation).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }

  "update with ChangeProductionApplicationTermsAndConditionsLocation" should {
    val oldLocation           = TermsAndConditionsLocation.InDesktopSoftware
    val newLocation           = TermsAndConditionsLocation.Url("http://example.com")
    val changeTermsConditionsLocation = ChangeProductionApplicationTermsAndConditionsLocation(instigator, timestamp, newLocation)
    val event                         = ProductionAppTermsConditionsLocationChanged(UpdateApplicationEvent.Id.random, applicationId, timestamp, CollaboratorActor(adminEmail), oldLocation, newLocation, adminEmail)

    def setTermsAndConditionsLocation(app: ApplicationData, location: TermsAndConditionsLocation) = {
      app.access match {
        case Standard(_, _, _, _, _, Some(importantSubmissionData)) => app.copy(access = app.access.asInstanceOf[Standard].copy(importantSubmissionData = Some(importantSubmissionData.copy(termsAndConditionsLocation = location))))
        case _ => fail("Unexpected access type: " + app.access)
      }
    }

    "return the updated application if the application exists" in new Setup {
      val appBefore = setTermsAndConditionsLocation(applicationData, oldLocation)
      val appAfter = setTermsAndConditionsLocation(applicationData, newLocation)
      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()

      when(mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationTermsAndConditionsLocation])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, changeTermsConditionsLocation).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      result shouldBe Right(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.verifyCalledWith(NonEmptyList.one(event))
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, changeTermsConditionsLocation).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }

  "update with ChangeResponsibleIndividualToSelf" should {
    val changeResponsibleIndividual = ChangeResponsibleIndividualToSelf(UserId.random, LocalDateTime.now, "name", "email")

    "return the updated application if the application exists" in new Setup {
      val newRiName = "Mr Responsible"
      val newRiEmail = "ri@example.com"
      val appBefore = applicationData
      val appAfter = applicationData.copy(access = Standard(
        importantSubmissionData = Some(testImportantSubmissionData.copy(
          responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)))))
      val event = ResponsibleIndividualChanged(
        UpdateApplicationEvent.Id.random, applicationId, timestamp,
        CollaboratorActor(changeResponsibleIndividual.email),
        newRiName, newRiEmail, Submission.Id.random, 1, changeResponsibleIndividual.email)
      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()

      when(mockChangeResponsibleIndividualToSelfCommandHandler.process(*[ApplicationData], *[ChangeResponsibleIndividualToSelf])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, changeResponsibleIndividual).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      result shouldBe Right(appAfter)
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, changeResponsibleIndividual).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }

  "update with ChangeResponsibleIndividualToOther" should {
    val code = "235345t3874528745379534234234234"
    val changeResponsibleIndividual = ChangeResponsibleIndividualToOther(code, LocalDateTime.now)
    val requesterEmail = "bill.badger@rupert.com"
    val requesterName = "bill badger"
    val appInPendingRIVerification = applicationData.copy(state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail, requesterName))

    "return the updated application if the application exists" in new Setup {
      val newRiName = "Mr Responsible"
      val newRiEmail = "ri@example.com"
      val appBefore = appInPendingRIVerification
      val appAfter = appInPendingRIVerification.copy(access = Standard(
        importantSubmissionData = Some(testImportantSubmissionData.copy(
          responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)))))
      val riSetEvent = ResponsibleIndividualSet(
        UpdateApplicationEvent.Id.random, applicationId, timestamp,
        CollaboratorActor(requesterEmail),
        newRiName, newRiEmail, Submission.Id.random, 1, code, requesterEmail)
      val stateEvent = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random, applicationId, timestamp,
        CollaboratorActor(requesterEmail),
        State.PENDING_GATEKEEPER_APPROVAL, State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION, 
        requesterEmail, requesterName)
      val events = NonEmptyList.of(riSetEvent, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()

      when(mockChangeResponsibleIndividualToOtherCommandHandler.process(*[ApplicationData], *[ChangeResponsibleIndividualToOther])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, changeResponsibleIndividual).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(riSetEvent, stateEvent)
      result shouldBe Right(appAfter)
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, changeResponsibleIndividual).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }

  "update with VerifyResponsibleIndividual" should {
    val adminName = "Ms Admin"
    val verifyResponsibleIndividual = VerifyResponsibleIndividual(UserId.random, LocalDateTime.now, adminName, "name", "email")

    "return the updated application if the application exists" in new Setup {
      val newRiName = "Mr Responsible"
      val newRiEmail = "ri@example.com"
      val app = applicationData
      val appName = applicationData.name
      val event = ResponsibleIndividualVerificationStarted(
        UpdateApplicationEvent.Id.random, applicationId, appName, timestamp,
        CollaboratorActor(verifyResponsibleIndividual.riEmail), adminName, adminEmail,
        newRiName, newRiEmail, Submission.Id.random, 1, ResponsibleIndividualVerificationId.random)
      ApplicationRepoMock.Fetch.thenReturn(app)
      ApplicationRepoMock.ApplyEvents.thenReturn(app)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()

      when(mockVerifyResponsibleIndividualCommandHandler.process(*[ApplicationData], *[VerifyResponsibleIndividual])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, verifyResponsibleIndividual).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      result shouldBe Right(app)
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, verifyResponsibleIndividual).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }
}
