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

import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationCommandServiceUtils
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, FixedClock}

class ApplicationCommandServiceCollaboratorSpec extends ApplicationCommandServiceUtils with ApplicationTestData {

  trait Setup extends CommonSetup

  val timestamp             = FixedClock.now
  val gatekeeperUser        = "gkuser1"
  val adminName             = "Mr Admin"
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

  "update with RemoveCollaborator" should {

    val requesterEmail = "bill.badger@rupert.com"

    "return the updated application if the application exists" in new Setup {

      val someAdmin         = "someAdmin@company"
      val collaboratorEmail = "someone@somecompany"
      val collaborator      = Collaborator(collaboratorEmail, Role.DEVELOPER, idOf(collaboratorEmail))

      val adminsToEmail                               = Set("1@company.com", "2@company.com")
      val appBefore                                   = applicationData
      val appAfter                                    = appBefore.copy(collaborators = applicationData.collaborators ++ Set(collaborator))
      val collaboratorRemovedEvt: CollaboratorRemoved = CollaboratorRemoved(
        UpdateApplicationEvent.Id.random,
        applicationId,
        timestamp,
        CollaboratorActor(requesterEmail),
        collaborator.userId,
        collaborator.emailAddress,
        collaborator.role,
        notifyCollaborator = true,
        adminsToEmail
      )

      val removeCollaborator = RemoveCollaborator(CollaboratorActor(someAdmin), collaborator, adminsToEmail, timestamp)

      val events = NonEmptyList.of(collaboratorRemovedEvt)

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

      when(mockRemoveCollaboratorCommandHandler.process(*[ApplicationData], *[RemoveCollaborator])).thenReturn(
        Future.successful(Validated.valid(events).toValidatedNec)
      )

      val result = await(underTest.update(applicationId, removeCollaborator).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(collaboratorRemovedEvt)
      AuditServiceMock.ApplyEvents.verifyCalledWith(appAfter, NonEmptyList.one(collaboratorRemovedEvt))
      result shouldBe Right(appAfter)
    }
  }

}
