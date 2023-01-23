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

package uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import scala.concurrent.Future

import cats.data.{NonEmptyChain, NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationUpdateServiceUtils
import uk.gov.hmrc.thirdpartyapplication.util._
import java.time.LocalDateTime

class ApplicationUpdateServiceSpec extends ApplicationUpdateServiceUtils
    with UpliftRequestSamples {

  trait Setup extends CommonSetup

  val timestamp             = FixedClock.now
  val gatekeeperUser        = "gkuser1"
  val adminName             = "Mr Admin"
  val adminEmail            = "admin@example.com"
  val devHubUser            = CollaboratorActor(adminEmail)
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
    ResponsibleIndividualVerificationId.random,
    applicationId,
    submissionId,
    1,
    applicationData.name,
    timestamp,
    responsibleIndividual,
    adminName,
    adminEmail
  )
  val instigator     = applicationData.collaborators.head.userId

  "update with ChangeProductionApplicationName" should {
    val newName    = "robs new app"
    val changeName = ChangeProductionApplicationName(instigator, timestamp, gatekeeperUser, newName)
    val event      = ProductionAppNameChanged(
      UpdateApplicationEvent.Id.random,
      applicationId,
      FixedClock.now,
      UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser),
      applicationData.name,
      newName,
      adminEmail
    )

    "return the updated application if the application exists" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      val appAfter = applicationData.copy(name = newName)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      AuditServiceMock.ApplyEvents.succeeds

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
    val oldLocation                 = PrivacyPolicyLocation.InDesktopSoftware
    val newLocation                 = PrivacyPolicyLocation.Url("http://example.com")
    val changePrivacyPolicyLocation = ChangeProductionApplicationPrivacyPolicyLocation(instigator, timestamp, newLocation)
    val event                       = ProductionAppPrivacyPolicyLocationChanged(UpdateApplicationEvent.Id.random, applicationId, timestamp, devHubUser, oldLocation, newLocation)

    def setPrivacyPolicyLocation(app: ApplicationData, location: PrivacyPolicyLocation) = {
      app.access match {
        case Standard(_, _, _, _, _, Some(importantSubmissionData)) =>
          app.copy(access = app.access.asInstanceOf[Standard].copy(importantSubmissionData = Some(importantSubmissionData.copy(privacyPolicyLocation = location))))
        case _                                                      => fail("Unexpected access type: " + app.access)
      }
    }

    "return the updated application if the application exists" in new Setup {
      val appBefore = setPrivacyPolicyLocation(applicationData, oldLocation)
      val appAfter  = setPrivacyPolicyLocation(applicationData, newLocation)
      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      SubmissionsServiceMock.ApplyEvents.succeeds()
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

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
    val oldLocation                   = TermsAndConditionsLocation.InDesktopSoftware
    val newLocation                   = TermsAndConditionsLocation.Url("http://example.com")
    val changeTermsConditionsLocation = ChangeProductionApplicationTermsAndConditionsLocation(instigator, timestamp, newLocation)
    val event                         = ProductionAppTermsConditionsLocationChanged(UpdateApplicationEvent.Id.random, applicationId, timestamp, devHubUser, oldLocation, newLocation)

    def setTermsAndConditionsLocation(app: ApplicationData, location: TermsAndConditionsLocation) = {
      app.access match {
        case Standard(_, _, _, _, _, Some(importantSubmissionData)) =>
          app.copy(access = app.access.asInstanceOf[Standard].copy(importantSubmissionData = Some(importantSubmissionData.copy(termsAndConditionsLocation = location))))
        case _                                                      => fail("Unexpected access type: " + app.access)
      }
    }

    "return the updated application if the application exists" in new Setup {
      val appBefore = setTermsAndConditionsLocation(applicationData, oldLocation)
      val appAfter  = setTermsAndConditionsLocation(applicationData, newLocation)
      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

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
    val changeResponsibleIndividual = ChangeResponsibleIndividualToSelf(UserId.random, FixedClock.now, "name", "email")

    "return the updated application if the application exists" in new Setup {
      val newRiName  = "Mr Responsible"
      val newRiEmail = "ri@example.com"
      val code       = "656474284925734543643"
      val appBefore  = applicationData
      val appAfter   = applicationData.copy(access =
        Standard(
          importantSubmissionData = Some(testImportantSubmissionData.copy(
            responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)
          ))
        )
      )
      val event      = ResponsibleIndividualChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(changeResponsibleIndividual.email),
        "bob example",
        "bob@example.com",
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        code,
        changeResponsibleIndividual.name,
        changeResponsibleIndividual.email
      )
      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      SubmissionsServiceMock.ApplyEvents.succeeds()
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

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
    val code                        = "235345t3874528745379534234234234"
    val changeResponsibleIndividual = ChangeResponsibleIndividualToOther(code, FixedClock.now)
    val requesterEmail              = "bill.badger@rupert.com"
    val requesterName               = "bill badger"
    val appInPendingRIVerification  = applicationData.copy(state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail, requesterName))

    "return the updated application if the application exists" in new Setup {
      val newRiName  = "Mr Responsible"
      val newRiEmail = "ri@example.com"
      val appBefore  = appInPendingRIVerification
      val appAfter   = appInPendingRIVerification.copy(access =
        Standard(
          importantSubmissionData = Some(testImportantSubmissionData.copy(
            responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)
          ))
        )
      )
      val riSetEvent = ResponsibleIndividualSet(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        code,
        requesterName,
        requesterEmail
      )
      val stateEvent = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        State.PENDING_GATEKEEPER_APPROVAL,
        State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
        requesterEmail,
        requesterName
      )
      val events     = NonEmptyList.of(riSetEvent, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

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
    val adminName                   = "Ms Admin"
    val verifyResponsibleIndividual = VerifyResponsibleIndividual(UserId.random, FixedClock.now, adminName, "name", "email")

    "return the updated application if the application exists" in new Setup {
      val newRiName  = "Mr Responsible"
      val newRiEmail = "ri@example.com"
      val app        = applicationData
      val appName    = applicationData.name
      val event      = ResponsibleIndividualVerificationStarted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        appName,
        timestamp,
        CollaboratorActor(verifyResponsibleIndividual.riEmail),
        adminName,
        adminEmail,
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        ResponsibleIndividualVerificationId.random
      )
      ApplicationRepoMock.Fetch.thenReturn(app)
      ApplicationRepoMock.ApplyEvents.thenReturn(app)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

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

  "update with DeclineResponsibleIndividual" should {
    val code                         = "235345t3874528745379534234234234"
    val declineResponsibleIndividual = DeclineResponsibleIndividual(code, FixedClock.now)
    val requesterEmail               = "bill.badger@rupert.com"
    val requesterName                = "bill badger"
    val appInPendingRIVerification   = applicationData.copy(state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail, requesterName))

    "return the updated application if the application exists" in new Setup {
      val newRiName                  = "Mr Responsible"
      val newRiEmail                 = "ri@example.com"
      val reasons                    = "reasons"
      val appBefore                  = appInPendingRIVerification
      val appAfter                   = appInPendingRIVerification.copy(access =
        Standard(
          importantSubmissionData = Some(testImportantSubmissionData.copy(
            responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)
          ))
        )
      )
      val riDeclined                 = ResponsibleIndividualDeclined(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        code,
        requesterName,
        requesterEmail
      )
      val appApprovalRequestDeclined = ApplicationApprovalRequestDeclined(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        reasons,
        requesterName,
        requesterEmail
      )
      val stateEvent                 = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        State.PENDING_GATEKEEPER_APPROVAL,
        State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
        requesterEmail,
        requesterName
      )
      val events                     = NonEmptyList.of(riDeclined, appApprovalRequestDeclined, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeclineResponsibleIndividualCommandHandler.process(*[ApplicationData], *[DeclineResponsibleIndividual])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, declineResponsibleIndividual).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(riDeclined, appApprovalRequestDeclined, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

  "update with DeclineResponsibleIndividualDidNotVerify" should {
    val code                                     = "235345t3874528745379534234234234"
    val declineResponsibleIndividualDidNotVerify = DeclineResponsibleIndividualDidNotVerify(code, FixedClock.now)
    val requesterEmail                           = "bill.badger@rupert.com"
    val requesterName                            = "bill badger"
    val appInPendingRIVerification               = applicationData.copy(state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail, requesterName))

    "return the updated application if the application exists" in new Setup {
      val newRiName                  = "Mr Responsible"
      val newRiEmail                 = "ri@example.com"
      val reasons                    = "reasons"
      val appBefore                  = appInPendingRIVerification
      val appAfter                   = appInPendingRIVerification.copy(access =
        Standard(
          importantSubmissionData = Some(testImportantSubmissionData.copy(
            responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)
          ))
        )
      )
      val riDidNotVerify             = ResponsibleIndividualDidNotVerify(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        code,
        requesterName,
        requesterEmail
      )
      val appApprovalRequestDeclined = ApplicationApprovalRequestDeclined(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        reasons,
        requesterName,
        requesterEmail
      )
      val stateEvent                 = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        State.PENDING_GATEKEEPER_APPROVAL,
        State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
        requesterEmail,
        requesterName
      )
      val events                     = NonEmptyList.of(riDidNotVerify, appApprovalRequestDeclined, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeclineResponsibleIndividualDidNotVerifyCommandHandler.process(*[ApplicationData], *[DeclineResponsibleIndividualDidNotVerify])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, declineResponsibleIndividualDidNotVerify).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(riDidNotVerify, appApprovalRequestDeclined, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

  "update with DeclineApplicationApprovalRequest" should {
    val gatekeeperUser                    = "Bob.TheBuilder"
    val reasons                           = "Reasons description text"
    val declineApplicationApprovalRequest = DeclineApplicationApprovalRequest(gatekeeperUser, reasons, FixedClock.now)
    val requesterEmail                    = "bill.badger@rupert.com"
    val requesterName                     = "bill badger"
    val appInPendingRIVerification        = applicationData.copy(state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail, requesterName))

    "return the updated application if the application exists" in new Setup {
      val newRiName                  = "Mr Responsible"
      val newRiEmail                 = "ri@example.com"
      val appBefore                  = appInPendingRIVerification
      val appAfter                   = appInPendingRIVerification.copy(access =
        Standard(
          importantSubmissionData = Some(testImportantSubmissionData.copy(
            responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)
          ))
        )
      )
      val appApprovalRequestDeclined = ApplicationApprovalRequestDeclined(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        newRiName,
        newRiEmail,
        Submission.Id.random,
        1,
        reasons,
        requesterName,
        requesterEmail
      )
      val stateEvent                 = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        State.PENDING_GATEKEEPER_APPROVAL,
        State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
        requesterEmail,
        requesterName
      )
      val events                     = NonEmptyList.of(appApprovalRequestDeclined, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeclineApplicationApprovalRequestCommandHandler.process(*[ApplicationData], *[DeclineApplicationApprovalRequest])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, declineApplicationApprovalRequest).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(appApprovalRequestDeclined, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

  "update with DeleteApplicationByCollaborator" should {
    val instigator                      = UserId.random
    val requesterEmail                  = "bill.badger@rupert.com"
    val actor                           = CollaboratorActor(requesterEmail)
    val reasons                         = "Reasons description text"
    val deleteApplicationByCollaborator = DeleteApplicationByCollaborator(instigator, reasons, FixedClock.now)
    val clientId                        = ClientId("clientId")
    val appInDeletedState               = applicationData.copy(state = ApplicationState.deleted(requesterEmail, requesterEmail))

    "return the updated application if the application exists" in new Setup {
      val appBefore = applicationData
      val appAfter  = appInDeletedState

      val applicationDeleted = ApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        clientId,
        "wso2ApplicationName",
        "reasons"
      )
      val stateEvent         = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        State.PRODUCTION,
        State.DELETED,
        requesterEmail,
        requesterEmail
      )
      val events             = NonEmptyList.of(applicationDeleted, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeleteApplicationByCollaboratorCommandHandler.process(*[ApplicationData], *[DeleteApplicationByCollaborator])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, deleteApplicationByCollaborator).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(applicationDeleted, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

  "update with DeleteApplicationByGatekeeper" should {
    val requesterEmail                = "bill.badger@rupert.com"
    val gatekeeperUser                = "gatekeeperuser"
    val actor                         = GatekeeperUserActor(gatekeeperUser)
    val reasons                       = "Reasons description text"
    val deleteApplicationByGatekeeper = DeleteApplicationByGatekeeper(gatekeeperUser, requesterEmail, reasons, FixedClock.now)
    val clientId                      = ClientId("clientId")
    val appInDeletedState             = applicationData.copy(state = ApplicationState.deleted(requesterEmail, requesterEmail))

    "return the updated application if the application exists" in new Setup {
      val appBefore = applicationData
      val appAfter  = appInDeletedState

      val applicationDeletedByGatekeeper = ApplicationDeletedByGatekeeper(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        clientId,
        "wso2ApplicationName",
        "reasons",
        requesterEmail
      )
      val stateEvent                     = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        State.PRODUCTION,
        State.DELETED,
        requesterEmail,
        requesterEmail
      )
      val events                         = NonEmptyList.of(applicationDeletedByGatekeeper, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeleteApplicationByGatekeeperCommandHandler.process(*[ApplicationData], *[DeleteApplicationByGatekeeper])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, deleteApplicationByGatekeeper).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(applicationDeletedByGatekeeper, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

  "update with DeleteUnusedApplication" should {
    val actor                   = ScheduledJobActor("DeleteUnusedApplicationsJob")
    val reasons                 = "Reasons description text"
    val authorisationKey        = "23476523467235972354923"
    val deleteUnusedApplication = DeleteUnusedApplication("DeleteUnusedApplicationsJob", authorisationKey, reasons, FixedClock.now)
    val requesterEmail          = "bill.badger@rupert.com"
    val clientId                = ClientId("clientId")
    val appInDeletedState       = applicationData.copy(state = ApplicationState.deleted(requesterEmail, requesterEmail))

    "return the updated application if the application exists" in new Setup {
      val appBefore = applicationData
      val appAfter  = appInDeletedState

      val applicationDeleted = ApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        clientId,
        "wso2ApplicationName",
        "reasons"
      )
      val stateEvent         = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        State.PRODUCTION,
        State.DELETED,
        requesterEmail,
        requesterEmail
      )
      val events             = NonEmptyList.of(applicationDeleted, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeleteUnusedApplicationCommandHandler.process(*[ApplicationData], *[DeleteUnusedApplication])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, deleteUnusedApplication).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(applicationDeleted, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

  "update with ProductionCredentialsApplicationDeleted" should {
    val jobId                                  = "ProductionCredentialsRequestExpiredJob"
    val actor                                  = ScheduledJobActor(jobId)
    val reasons                                = "Reasons description text"
    val deleteProductionCredentialsApplication = DeleteProductionCredentialsApplication(jobId, reasons, FixedClock.now)
    val requesterEmail                         = "bill.badger@rupert.com"
    val clientId                               = ClientId("clientId")
    val appInDeletedState                      = applicationData.copy(state = ApplicationState.deleted(requesterEmail, requesterEmail))

    "return the updated application if the application exists" in new Setup {
      val appBefore = applicationData
      val appAfter  = appInDeletedState

      val productionCredentialsApplicationDeleted = ProductionCredentialsApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        clientId,
        "wso2AppName",
        "reasons"
      )
      val stateEvent                              = ApplicationStateChanged(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        actor,
        State.PRODUCTION,
        State.DELETED,
        requesterEmail,
        requesterEmail
      )

      val events = NonEmptyList.of(productionCredentialsApplicationDeleted, stateEvent)

      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.succeeds
      NotificationServiceMock.SendNotifications.thenReturnSuccess()
      SubmissionsServiceMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      SubscriptionRepoMock.ApplyEvents.succeeds()
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeleteProductionCredentialsApplicationCommandHandler.process(*[ApplicationData], *[DeleteProductionCredentialsApplication])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, deleteProductionCredentialsApplication).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(productionCredentialsApplicationDeleted, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

}
