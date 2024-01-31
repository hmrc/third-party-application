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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{PrivacyPolicyLocations, TermsAndConditionsLocations, _}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerificationId,
  ResponsibleIndividualVerificationState
}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeResponsibleIndividualToOther
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationStateExamples
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{
  ApplicationRepositoryMockModule,
  ResponsibleIndividualVerificationRepositoryMockModule,
  StateHistoryRepositoryMockModule,
  TermsOfUseInvitationRepositoryMockModule
}

class ChangeResponsibleIndividualToOtherCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData with FixedClock {

  trait Setup extends ResponsibleIndividualVerificationRepositoryMockModule with ApplicationRepositoryMockModule with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule with SubmissionsServiceMockModule {

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
      access = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)),
      state = ApplicationStateExamples.pendingResponsibleIndividualVerification(requesterEmail.text, requesterName)
    )
    val ts   = FixedClock.instant
    val code = "3242342387452384623549234"

    val riVerificationToU = ResponsibleIndividualToUVerification(
      ResponsibleIndividualVerificationId(code),
      appId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      instant,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val riVerificationTouUplift = ResponsibleIndividualTouUpliftVerification(
      ResponsibleIndividualVerificationId(code),
      appId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      instant,
      requesterName,
      requesterEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val riVerificationUpdate = ResponsibleIndividualUpdateVerification(
      ResponsibleIndividualVerificationId(code),
      appId,
      submission.id,
      submission.latestInstance.index,
      "App Name",
      instant,
      newResponsibleIndividual,
      requesterName,
      requesterEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val underTest =
      new ChangeResponsibleIndividualToOtherCommandHandler(
        ApplicationRepoMock.aMock,
        ResponsibleIndividualVerificationRepositoryMock.aMock,
        StateHistoryRepoMock.aMock,
        TermsOfUseInvitationRepositoryMock.aMock,
        SubmissionsServiceMock.aMock,
        clock
      )

    def checkSuccessResultToU()(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

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

    def checkSuccessResultTouUplift(isPassed: Boolean)(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      if (isPassed) {
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
            case passEvent: TermsOfUsePassed =>
              passEvent.applicationId shouldBe appId
              passEvent.eventDateTime shouldBe ts
              passEvent.actor shouldBe Actors.AppCollaborator(appAdminEmail)
              passEvent.submissionIndex shouldBe submission.latestInstance.index
              passEvent.submissionId.value shouldBe submission.id.value
          }
        }
      } else {
        inside(testMe) { case (app, events) =>
          events should have size 1

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
        }
      }
    }

    def checkSuccessResultUpdate()(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

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
  }

  "process" should {
    "create correct event for a valid request with a ToU responsibleIndividualVerification and a standard app" in new Setup {

      val pendingRIApp = app.copy(state = ApplicationStateExamples.pendingResponsibleIndividualVerification(requesterEmail.text, requesterName))
      ApplicationRepoMock.UpdateApplicationSetResponsibleIndividual.thenReturn(pendingRIApp)
      ApplicationRepoMock.UpdateApplicationState.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      ResponsibleIndividualVerificationRepositoryMock.DeleteResponsibleIndividualVerification.thenReturnSuccess()
      StateHistoryRepoMock.Insert.succeeds()

      checkSuccessResultToU() {
        underTest.process(pendingRIApp, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "create correct event for a valid request with a Tou Uplift responsibleIndividualVerification, a standard app and a non-passing submission" in new Setup {

      val prodApp = app.copy(state = ApplicationStateExamples.production(requesterEmail.text, requesterName))
      ApplicationRepoMock.UpdateApplicationSetResponsibleIndividual.thenReturn(prodApp)
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationTouUplift)
      SubmissionsServiceMock.MarkSubmission.thenReturn(warningsSubmission)
      ResponsibleIndividualVerificationRepositoryMock.DeleteResponsibleIndividualVerification.thenReturnSuccess()
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()

      checkSuccessResultTouUplift(false) {
        underTest.process(prodApp, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "create correct event for a valid request with a Tou Uplift responsibleIndividualVerification, a standard app and a passing submission" in new Setup {

      val prodApp = app.copy(state = ApplicationStateExamples.production(requesterEmail.text, requesterName))
      ApplicationRepoMock.UpdateApplicationSetResponsibleIndividual.thenReturn(prodApp)
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationTouUplift)
      SubmissionsServiceMock.MarkSubmission.thenReturn(grantedSubmission)
      ApplicationRepoMock.AddApplicationTermsOfUseAcceptance.thenReturn(prodApp)
      ResponsibleIndividualVerificationRepositoryMock.DeleteResponsibleIndividualVerification.thenReturnSuccess()
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()

      checkSuccessResultTouUplift(true) {
        underTest.process(prodApp, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "create correct event for a valid request with an update responsibleIndividualVerification and a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationUpdate)
      ResponsibleIndividualVerificationRepositoryMock.DeleteResponsibleIndividualVerification.thenReturnSuccess()
      val prodApp = app.copy(state = ApplicationStateExamples.production(requesterEmail.text, requesterName))
      ApplicationRepoMock.UpdateApplicationChangeResponsibleIndividual.thenReturn(prodApp)

      checkSuccessResultUpdate() {
        underTest.process(prodApp, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturnNothing
      checkFailsWith(s"No responsibleIndividualVerification found for code $code") {
        underTest.process(app, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val nonStandardApp = app.copy(access = Access.Ropc(Set.empty))
      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(nonStandardApp, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "return an error if the application is old journey" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val oldJourneyApp = app.copy(access = Access.Standard(List.empty, None, None, Set.empty, None, None))
      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(oldJourneyApp, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "return an error if the application is is different between the request and the responsibleIndividualVerification record" in new Setup {
      val riVerification2 = ResponsibleIndividualToUVerification(
        ResponsibleIndividualVerificationId(code),
        ApplicationId.random,
        submission.id,
        submission.latestInstance.index,
        "App Name",
        instant,
        ResponsibleIndividualVerificationState.INITIAL
      )
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification2)
      checkFailsWith("The given application id is different") {
        underTest.process(app, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val pendingGKApprovalApp = app.copy(state = ApplicationStateExamples.pendingGatekeeperApproval(requesterEmail.text, requesterName))
      checkFailsWith("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state") {
        underTest.process(pendingGKApprovalApp, ChangeResponsibleIndividualToOther(code, instant))
      }
    }

  }

}
