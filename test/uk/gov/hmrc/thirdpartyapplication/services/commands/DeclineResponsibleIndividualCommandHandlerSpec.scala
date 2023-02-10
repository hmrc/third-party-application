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

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerificationId,
  ResponsibleIndividualVerificationState
}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.TermsAndConditionsLocations

class DeclineResponsibleIndividualCommandHandlerSpec
    extends AsyncHmrcSpec
    with ApplicationTestData
    with SubmissionsTestData
    with CommandActorExamples
    with CommandCollaboratorExamples
    with CommandApplicationExamples {

  trait Setup
      extends ResponsibleIndividualVerificationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with SubmissionsServiceMockModule
      with ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

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
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app  = anApplicationData(applicationId).copy(
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
      applicationId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      ts,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val riVerificationUpdate = ResponsibleIndividualUpdateVerification(
      ResponsibleIndividualVerificationId(code),
      applicationId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      ts,
      newResponsibleIndividual,
      requesterName,
      requesterEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val underTest = new DeclineResponsibleIndividualCommandHandler(
      ApplicationRepoMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      StateHistoryRepoMock.aMock,
      SubmissionsServiceMock.aMock
    )

    def checkSuccessResultToU()(fn: => CommandHandler.ResultT) = {
      val testMe = await(fn.value).right.value

      inside(testMe) { case (app, events) =>
        events should have size 3

        events.collect {
          case riDeclined: ResponsibleIndividualDeclined =>
            riDeclined.applicationId shouldBe applicationId
            riDeclined.eventDateTime shouldBe ts
            riDeclined.actor shouldBe Actors.Collaborator(appAdminEmail)
            riDeclined.responsibleIndividualName shouldBe riName
            riDeclined.responsibleIndividualEmail shouldBe riEmail
            riDeclined.submissionIndex shouldBe submission.latestInstance.index
            riDeclined.submissionId shouldBe submission.id
            riDeclined.requestingAdminEmail shouldBe appAdminEmail
            riDeclined.code shouldBe code
        }
        events.collect {
          case appApprovalRequestDeclined: ApplicationApprovalRequestDeclined =>
            appApprovalRequestDeclined.applicationId shouldBe applicationId
            appApprovalRequestDeclined.eventDateTime shouldBe ts
            appApprovalRequestDeclined.actor shouldBe Actors.Collaborator(appAdminEmail)
            appApprovalRequestDeclined.decliningUserName shouldBe riName
            appApprovalRequestDeclined.decliningUserEmail shouldBe riEmail
            appApprovalRequestDeclined.submissionIndex shouldBe submission.latestInstance.index
            appApprovalRequestDeclined.submissionId shouldBe submission.id
            appApprovalRequestDeclined.requestingAdminEmail shouldBe appAdminEmail
            appApprovalRequestDeclined.reasons shouldBe "Responsible individual declined the terms of use."
        }

        events.collect {
          case stateEvent: ApplicationStateChanged =>
            stateEvent.applicationId shouldBe applicationId
            stateEvent.eventDateTime shouldBe ts
            stateEvent.actor shouldBe Actors.Collaborator(appAdminEmail)
            stateEvent.requestingAdminEmail shouldBe requesterEmail
            stateEvent.requestingAdminName shouldBe requesterName
            stateEvent.newAppState shouldBe State.TESTING
            stateEvent.oldAppState shouldBe State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
        }
      }
    }

    def checkSuccessResultUpdate()(fn: => CommandHandler.ResultT) = {
      val testMe = await(fn.value).right.value

      inside(testMe) { case (app, events) =>
        events should have size 1

        events.collect {
          case riDeclined: ResponsibleIndividualDeclinedUpdate =>
            riDeclined.applicationId shouldBe applicationId
            riDeclined.eventDateTime shouldBe ts
            riDeclined.actor shouldBe Actors.Collaborator(appAdminEmail)
            riDeclined.responsibleIndividualName shouldBe newResponsibleIndividual.fullName.value
            riDeclined.responsibleIndividualEmail shouldBe newResponsibleIndividual.emailAddress.value
            riDeclined.submissionIndex shouldBe submission.latestInstance.index
            riDeclined.submissionId shouldBe submission.id
            riDeclined.requestingAdminEmail shouldBe appAdminEmail
            riDeclined.code shouldBe code
        }
      }
    }

    def checkFailsWith(msg: String, msgs: String*)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1 + msgs.length
      testThis.head shouldBe msg
      testThis.tail shouldBe msgs
    }
  }

  "process" should {
    "create correct event for a valid request with a ToU responsibleIndividualVerification and a standard app" in new Setup {
      ApplicationRepoMock.UpdateApplicationState.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      ResponsibleIndividualVerificationRepositoryMock.DeleteSubmissionInstance.succeeds()
      SubmissionsServiceMock.DeclineApprovalRequest.succeeds()
      StateHistoryRepoMock.AddRecord.succeeds()

      checkSuccessResultToU() {
        underTest.process(app, DeclineResponsibleIndividual(code, ts))
      }
    }

    "create correct event for a valid request with an update responsibleIndividualVerification and a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationUpdate)
      ResponsibleIndividualVerificationRepositoryMock.DeleteSubmissionInstance.succeeds()

      val prodApp = app.copy(state = ApplicationState.production(requesterEmail, requesterName))

      checkSuccessResultUpdate() {
        underTest.process(prodApp, DeclineResponsibleIndividual(code, ts))
      }
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturnNothing

      checkFailsWith(s"No responsibleIndividualVerification found for code $code") {
        underTest.process(app, DeclineResponsibleIndividual(code, ts))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))

      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(nonStandardApp, DeclineResponsibleIndividual(code, ts))
      }
    }

    "return an error if the application is old journey" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))

      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(oldJourneyApp, DeclineResponsibleIndividual(code, ts))
      }
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

      checkFailsWith("The given application id is different") {
        underTest.process(app, DeclineResponsibleIndividual(code, ts))
      }
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val pendingGKApprovalApp = app.copy(state = ApplicationState.pendingGatekeeperApproval(requesterEmail, requesterName))

      checkFailsWith("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state") {
        underTest.process(pendingGKApprovalApp, DeclineResponsibleIndividual(code, ts))
      }
    }
  }
}
