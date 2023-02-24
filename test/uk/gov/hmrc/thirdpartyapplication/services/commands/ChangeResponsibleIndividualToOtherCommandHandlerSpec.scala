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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, PrivacyPolicyLocations, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerificationId,
  ResponsibleIndividualVerificationState
}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, ResponsibleIndividualVerificationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}

class ChangeResponsibleIndividualToOtherCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup extends ResponsibleIndividualVerificationRepositoryMockModule with ApplicationRepositoryMockModule with StateHistoryRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId                    = ApplicationId.random
    val submission               = aSubmission
    val appAdminUserId           = UserId.random
    val appAdminEmail            = "admin@example.com".toLaxEmail
    val riName                   = "Mr Responsible"
    val riEmail                  = "ri@example.com".toLaxEmail
    val newResponsibleIndividual = ResponsibleIndividual.build("New RI", "new-ri@example")
    val oldRiName                = "old ri"
    val requesterEmail           = appAdminEmail
    val requesterName            = "mr admin"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail.text),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app  = anApplicationData(appId).copy(
      collaborators = Set(
        appAdminEmail.admin(appAdminUserId)
      ),
      access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)),
      state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail.text, requesterName)
    )
    val ts   = FixedClock.instant
    val code = "3242342387452384623549234"

    val riVerificationToU = ResponsibleIndividualToUVerification(
      ResponsibleIndividualVerificationId(code),
      appId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      FixedClock.now,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val riVerificationUpdate = ResponsibleIndividualUpdateVerification(
      ResponsibleIndividualVerificationId(code),
      appId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      FixedClock.now,
      newResponsibleIndividual,
      requesterName,
      requesterEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val underTest =
      new ChangeResponsibleIndividualToOtherCommandHandler(ApplicationRepoMock.aMock, ResponsibleIndividualVerificationRepositoryMock.aMock, StateHistoryRepoMock.aMock)

    def checkSuccessResultToU()(fn: => CommandHandler.ResultT) = {
      val testMe = await(fn.value).right.value

      inside(testMe) { case (app, events) =>
        events should have size 2

        events.collect {
          case riSet: ResponsibleIndividualSet =>
            riSet.applicationId shouldBe appId
            riSet.eventDateTime shouldBe ts
            riSet.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            riSet.responsibleIndividualName shouldBe riName
            riSet.responsibleIndividualEmail shouldBe riEmail
            riSet.submissionIndex shouldBe submission.latestInstance.index
            riSet.submissionId.value shouldBe submission.id.value
            riSet.requestingAdminEmail shouldBe appAdminEmail
            riSet.code shouldBe code
        }

        events.collect {
          case stateEvent: ApplicationStateChanged =>
            stateEvent.applicationId shouldBe appId
            stateEvent.eventDateTime shouldBe ts
            stateEvent.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            stateEvent.requestingAdminEmail shouldBe requesterEmail
            stateEvent.requestingAdminName shouldBe requesterName
            stateEvent.newAppState shouldBe State.PENDING_GATEKEEPER_APPROVAL.toString()
            stateEvent.oldAppState shouldBe State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION.toString()
        }
      }
    }

    def checkSuccessResultUpdate()(fn: => CommandHandler.ResultT) = {
      val testMe = await(fn.value).right.value

      inside(testMe) { case (app, events) =>
        events should have size 1

        events.collect {
          case riChanged: ResponsibleIndividualChanged =>
            riChanged.applicationId shouldBe appId
            riChanged.eventDateTime shouldBe ts
            riChanged.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            riChanged.newResponsibleIndividualName shouldBe newResponsibleIndividual.fullName.value
            riChanged.newResponsibleIndividualEmail shouldBe newResponsibleIndividual.emailAddress
            riChanged.previousResponsibleIndividualName shouldBe riName
            riChanged.previousResponsibleIndividualEmail shouldBe riEmail
            riChanged.submissionIndex shouldBe submission.latestInstance.index
            riChanged.submissionId.value shouldBe submission.id.value
            riChanged.requestingAdminEmail shouldBe appAdminEmail
            riChanged.code shouldBe code
        }
      }
    }

    def checkFailsWith(msg: String, msgs: String*)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1 + msgs.length
      testThis.head shouldBe CommandFailures.GenericFailure(msg)
      testThis.tail shouldBe msgs.map(CommandFailures.GenericFailure(_))
    }

  }

  "process" should {
    "create correct event for a valid request with a ToU responsibleIndividualVerification and a standard app" in new Setup {

      val prodApp = app.copy(state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail.text, requesterName))
      ApplicationRepoMock.UpdateApplicationSetResponsibleIndividual.thenReturn(prodApp)
      ApplicationRepoMock.UpdateApplicationState.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      ResponsibleIndividualVerificationRepositoryMock.DeleteResponsibleIndividualVerification.thenReturnSuccess()
      StateHistoryRepoMock.Insert.succeeds()

      checkSuccessResultToU() {
        underTest.process(prodApp, ChangeResponsibleIndividualToOther(code, FixedClock.now))
      }
    }

    "create correct event for a valid request with an update responsibleIndividualVerification and a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationUpdate)
      ResponsibleIndividualVerificationRepositoryMock.DeleteResponsibleIndividualVerification.thenReturnSuccess()
      val prodApp = app.copy(state = ApplicationState.production(requesterEmail.text, requesterName))
      ApplicationRepoMock.UpdateApplicationChangeResponsibleIndividual.thenReturn(prodApp)

      checkSuccessResultUpdate() {
        underTest.process(prodApp, ChangeResponsibleIndividualToOther(code, FixedClock.now))
      }
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturnNothing
      checkFailsWith(s"No responsibleIndividualVerification found for code $code") {
        underTest.process(app, ChangeResponsibleIndividualToOther(code, FixedClock.now))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(nonStandardApp, ChangeResponsibleIndividualToOther(code, FixedClock.now))
      }
    }

    "return an error if the application is old journey" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(oldJourneyApp, ChangeResponsibleIndividualToOther(code, FixedClock.now))
      }
    }

    "return an error if the application is is different between the request and the responsibleIndividualVerification record" in new Setup {
      val riVerification2 = ResponsibleIndividualToUVerification(
        ResponsibleIndividualVerificationId(code),
        ApplicationId.random,
        submission.id,
        submission.latestInstance.index,
        "App Name",
        FixedClock.now,
        ResponsibleIndividualVerificationState.INITIAL
      )
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification2)
      checkFailsWith("The given application id is different") {
        underTest.process(app, ChangeResponsibleIndividualToOther(code, FixedClock.now))
      }
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val pendingGKApprovalApp = app.copy(state = ApplicationState.pendingGatekeeperApproval(requesterEmail.text, requesterName))
      checkFailsWith("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state") {
        underTest.process(pendingGKApprovalApp, ChangeResponsibleIndividualToOther(code, FixedClock.now))
      }
    }

  }

}
