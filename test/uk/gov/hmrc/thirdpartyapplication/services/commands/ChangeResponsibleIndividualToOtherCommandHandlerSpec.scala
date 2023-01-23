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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import scala.concurrent.ExecutionContext.Implicits.global

import cats.data.NonEmptyChain
import cats.data.Validated.Invalid

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerificationId,
  ResponsibleIndividualVerificationState
}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ChangeResponsibleIndividualToOtherCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup extends ResponsibleIndividualVerificationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId                    = ApplicationId.random
    val submission               = aSubmission
    val appAdminUserId           = UserId.random
    val appAdminEmail            = "admin@example.com"
    val riName                   = "Mr Responsible"
    val riEmail                  = "ri@example.com"
    val newResponsibleIndividual = ResponsibleIndividual.build("New RI", "new-ri@example")
    val oldRiName                = "old ri"
    val requesterEmail           = appAdminEmail
    val requesterName            = "mr admin"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail),
      Set.empty,
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app  = anApplicationData(appId).copy(
      collaborators = Set(
        Collaborator(appAdminEmail, Role.ADMINISTRATOR, appAdminUserId)
      ),
      access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)),
      state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail, requesterName)
    )
    val ts   = FixedClock.now
    val code = "3242342387452384623549234"

    val riVerificationToU = ResponsibleIndividualToUVerification(
      ResponsibleIndividualVerificationId(code),
      appId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      ts,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val riVerificationUpdate = ResponsibleIndividualUpdateVerification(
      ResponsibleIndividualVerificationId(code),
      appId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      ts,
      newResponsibleIndividual,
      requesterName,
      requesterEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )
    val underTest            = new ChangeResponsibleIndividualToOtherCommandHandler(ResponsibleIndividualVerificationRepositoryMock.aMock)
  }

  "process" should {
    "create correct event for a valid request with a ToU responsibleIndividualVerification and a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)

      val result = await(underTest.process(app, ChangeResponsibleIndividualToOther(code, ts)))

      result.isValid shouldBe true
      result.toOption.get.length shouldBe 2
      val riSetEvent = result.toOption.get.head.asInstanceOf[ResponsibleIndividualSet]
      riSetEvent.applicationId shouldBe appId
      riSetEvent.eventDateTime shouldBe ts
      riSetEvent.actor shouldBe CollaboratorActor(appAdminEmail)
      riSetEvent.responsibleIndividualName shouldBe riName
      riSetEvent.responsibleIndividualEmail shouldBe riEmail
      riSetEvent.submissionIndex shouldBe submission.latestInstance.index
      riSetEvent.submissionId shouldBe submission.id
      riSetEvent.requestingAdminEmail shouldBe appAdminEmail
      riSetEvent.code shouldBe code

      val stateEvent = result.toOption.get.tail.head.asInstanceOf[ApplicationStateChanged]
      stateEvent.applicationId shouldBe appId
      stateEvent.eventDateTime shouldBe ts
      stateEvent.actor shouldBe CollaboratorActor(appAdminEmail)
      stateEvent.requestingAdminEmail shouldBe requesterEmail
      stateEvent.requestingAdminName shouldBe requesterName
      stateEvent.newAppState shouldBe State.PENDING_GATEKEEPER_APPROVAL
      stateEvent.oldAppState shouldBe State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
    }

    "create correct event for a valid request with an update responsibleIndividualVerification and a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationUpdate)

      val prodApp = app.copy(state = ApplicationState.production(requesterEmail, requesterName))
      val result  = await(underTest.process(prodApp, ChangeResponsibleIndividualToOther(code, ts)))

      result.isValid shouldBe true
      result.toOption.get.length shouldBe 1
      val riChangedEvent = result.toOption.get.head.asInstanceOf[ResponsibleIndividualChanged]
      riChangedEvent.applicationId shouldBe appId
      riChangedEvent.eventDateTime shouldBe ts
      riChangedEvent.actor shouldBe CollaboratorActor(appAdminEmail)
      riChangedEvent.previousResponsibleIndividualName shouldBe riName
      riChangedEvent.previousResponsibleIndividualEmail shouldBe riEmail
      riChangedEvent.newResponsibleIndividualName shouldBe newResponsibleIndividual.fullName.value
      riChangedEvent.newResponsibleIndividualEmail shouldBe newResponsibleIndividual.emailAddress.value
      riChangedEvent.submissionIndex shouldBe submission.latestInstance.index
      riChangedEvent.submissionId shouldBe submission.id
      riChangedEvent.requestingAdminEmail shouldBe appAdminEmail
      riChangedEvent.code shouldBe code
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturnNothing
      val result = await(underTest.process(app, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.one(s"No responsibleIndividualVerification found for code $code"))
    }

    "return an error if the application is non-standard" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      val result         = await(underTest.process(nonStandardApp, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application"))
    }

    "return an error if the application is old journey" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      val result        = await(underTest.process(oldJourneyApp, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application"))
    }

    "return an error if the application is is different between the request and the responsibleIndividualVerification record" in new Setup {
      val riVerification2 = ResponsibleIndividualToUVerification(
        ResponsibleIndividualVerificationId(code),
        ApplicationId.random,
        submission.id,
        submission.latestInstance.index,
        "App Name",
        ts,
        ResponsibleIndividualVerificationState.INITIAL
      )
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification2)
      val result          = await(underTest.process(app, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.one("The given application id is different"))
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val pendingGKApprovalApp = app.copy(state = ApplicationState.pendingGatekeeperApproval(requesterEmail, requesterName))
      val result               = await(underTest.process(pendingGKApprovalApp, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.one("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state"))
    }
  }
}
