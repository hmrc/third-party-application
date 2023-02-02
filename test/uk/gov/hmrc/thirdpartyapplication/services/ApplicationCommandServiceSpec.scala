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

import scala.concurrent.Future

import cats.data.{NonEmptyChain, NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationCommandServiceUtils
import uk.gov.hmrc.thirdpartyapplication.util.{FixedClock, _}

class ApplicationCommandServiceSpec extends ApplicationCommandServiceUtils
    with UpliftRequestSamples {

  trait Setup extends CommonSetup

  val timestamp             = FixedClock.now
  val gatekeeperUser        = "gkuser1"
  val adminName             = "Mr Admin"
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
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeclineResponsibleIndividualCommandHandler.process(*[ApplicationData], *[DeclineResponsibleIndividual])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, declineResponsibleIndividual).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(riDeclined, appApprovalRequestDeclined, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

  // "update with DeclineResponsibleIndividualDidNotVerify" should {
  //   val code                                     = "235345t3874528745379534234234234"
  //   val declineResponsibleIndividualDidNotVerify = DeclineResponsibleIndividualDidNotVerify(code, FixedClock.now)
  //   val requesterEmail                           = "bill.badger@rupert.com"
  //   val requesterName                            = "bill badger"
  //   val appInPendingRIVerification               = applicationData.copy(state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail, requesterName))

  //   "return the updated application if the application exists" in new Setup {
  //     val newRiName                  = "Mr Responsible"
  //     val newRiEmail                 = "ri@example.com"
  //     val reasons                    = "reasons"
  //     val appBefore                  = appInPendingRIVerification
  //     val appAfter                   = appInPendingRIVerification.copy(access =
  //       Standard(
  //         importantSubmissionData = Some(testImportantSubmissionData.copy(
  //           responsibleIndividual = ResponsibleIndividual.build(newRiName, newRiEmail)
  //         ))
  //       )
  //     )
  //     val riDidNotVerify             = ResponsibleIndividualDidNotVerify(
  //       UpdateApplicationEvent.Id.random,
  //       applicationId,
  //       timestamp,
  //       CollaboratorActor(requesterEmail),
  //       newRiName,
  //       newRiEmail,
  //       Submission.Id.random,
  //       1,
  //       code,
  //       requesterName,
  //       requesterEmail
  //     )
  //     val appApprovalRequestDeclined = ApplicationApprovalRequestDeclined(
  //       UpdateApplicationEvent.Id.random,
  //       applicationId,
  //       timestamp,
  //       CollaboratorActor(requesterEmail),
  //       newRiName,
  //       newRiEmail,
  //       Submission.Id.random,
  //       1,
  //       reasons,
  //       requesterName,
  //       requesterEmail
  //     )
  //     val stateEvent                 = ApplicationStateChanged(
  //       UpdateApplicationEvent.Id.random,
  //       applicationId,
  //       timestamp,
  //       CollaboratorActor(requesterEmail),
  //       State.PENDING_GATEKEEPER_APPROVAL,
  //       State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
  //       requesterEmail,
  //       requesterName
  //     )
  //     val events                     = NonEmptyList.of(riDidNotVerify, appApprovalRequestDeclined, stateEvent)

  //     ApplicationRepoMock.Fetch.thenReturn(appBefore)
  //     ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
  //     ApiPlatformEventServiceMock.ApplyEvents.succeeds
  //     NotificationServiceMock.SendNotifications.thenReturnSuccess()
  //     SubmissionsServiceMock.ApplyEvents.succeeds()
  //     ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
  //     NotificationRepositoryMock.ApplyEvents.succeeds()
  //     StateHistoryRepoMock.ApplyEvents.succeeds()
  //     ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
  //     ApiGatewayStoreMock.ApplyEvents.succeeds()
  //     AuditServiceMock.ApplyEvents.succeeds

  //     when(mockDeclineResponsibleIndividualDidNotVerifyCommandHandler.process(*[ApplicationData], *[DeclineResponsibleIndividualDidNotVerify])).thenReturn(
  //       Future.successful(Validated.valid(events).toValidatedNec)
  //     )

  //     val result = await(underTest.update(applicationId, declineResponsibleIndividualDidNotVerify).value)

  //     ApplicationRepoMock.ApplyEvents.verifyCalledWith(riDidNotVerify, appApprovalRequestDeclined, stateEvent)
  //     result shouldBe Right(appAfter)
  //   }
  // }

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
      AuditServiceMock.ApplyEvents.succeeds

      when(mockDeclineApplicationApprovalRequestCommandHandler.process(*[ApplicationData], *[DeclineApplicationApprovalRequest])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, declineApplicationApprovalRequest).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(appApprovalRequestDeclined, stateEvent)
      result shouldBe Right(appAfter)
    }
  }

}
