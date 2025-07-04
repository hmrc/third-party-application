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

package uk.gov.hmrc.thirdpartyapplication.services.commands.submission

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ImportantSubmissionData, PrivacyPolicyLocations, ResponsibleIndividual, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerificationId,
  ResponsibleIndividualVerificationState
}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeclineResponsibleIndividual
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{
  ApplicationRepositoryMockModule,
  ResponsibleIndividualVerificationRepositoryMockModule,
  StateHistoryRepositoryMockModule,
  TermsOfUseInvitationRepositoryMockModule
}
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec}

class DeclineResponsibleIndividualCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup
      extends ResponsibleIndividualVerificationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule
      with SubmissionsServiceMockModule
      with ApplicationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission               = aSubmission
    val riName                   = "Mr Responsible"
    val riEmail                  = "ri@example.com".toLaxEmail
    val newResponsibleIndividual = ResponsibleIndividual.build("New RI", "new-ri@example")
    val oldRiName                = "old ri"
    val requesterEmail           = adminOne.emailAddress
    val requesterName            = "mr admin"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail.text),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app  = storedApp.copy(
      collaborators = Set(adminOne),
      access = Access.Standard(List.empty, List.empty, None, None, Set.empty, None, Some(importantSubmissionData)),
      state = ApplicationStateExamples.pendingResponsibleIndividualVerification(requesterEmail.text, requesterName)
    )
    val ts   = FixedClock.instant
    val code = "3242342387452384623549234"

    val riVerificationToU = ResponsibleIndividualToUVerification(
      ResponsibleIndividualVerificationId(code),
      applicationId,
      submission.id,
      submission.latestInstance.index,
      ApplicationName("App Name"),
      instant,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val riVerificationTouUplift = ResponsibleIndividualTouUpliftVerification(
      ResponsibleIndividualVerificationId(code),
      applicationId,
      submission.id,
      submission.latestInstance.index,
      ApplicationName("App Name"),
      instant,
      requesterName,
      requesterEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val riVerificationUpdate = ResponsibleIndividualUpdateVerification(
      ResponsibleIndividualVerificationId(code),
      applicationId,
      submission.id,
      submission.latestInstance.index,
      ApplicationName("App Name"),
      instant,
      newResponsibleIndividual,
      requesterName,
      requesterEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )

    val underTest = new DeclineResponsibleIndividualCommandHandler(
      ApplicationRepoMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      StateHistoryRepoMock.aMock,
      TermsOfUseInvitationRepositoryMock.aMock,
      SubmissionsServiceMock.aMock
    )

    def checkSuccessResultToU()(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (returnedApp, events) =>
        events should have size 3

        events.collect {
          case riDeclined: ApplicationEvents.ResponsibleIndividualDeclined =>
            riDeclined.applicationId shouldBe applicationId
            riDeclined.eventDateTime shouldBe ts
            riDeclined.actor shouldBe Actors.AppCollaborator(adminOne.emailAddress)
            riDeclined.responsibleIndividualName shouldBe riName
            riDeclined.responsibleIndividualEmail shouldBe riEmail
            riDeclined.submissionIndex shouldBe submission.latestInstance.index
            riDeclined.submissionId.value shouldBe submission.id.value
            riDeclined.requestingAdminEmail shouldBe adminOne.emailAddress
            riDeclined.code shouldBe code
        }
        events.collect {
          case appApprovalRequestDeclined: ApplicationEvents.ApplicationApprovalRequestDeclined =>
            appApprovalRequestDeclined.applicationId shouldBe applicationId
            appApprovalRequestDeclined.eventDateTime shouldBe ts
            appApprovalRequestDeclined.actor shouldBe Actors.AppCollaborator(adminOne.emailAddress)
            appApprovalRequestDeclined.decliningUserName shouldBe riName
            appApprovalRequestDeclined.decliningUserEmail shouldBe riEmail
            appApprovalRequestDeclined.submissionIndex shouldBe submission.latestInstance.index
            appApprovalRequestDeclined.submissionId.value shouldBe submission.id.value
            appApprovalRequestDeclined.requestingAdminEmail shouldBe adminOne.emailAddress
            appApprovalRequestDeclined.reasons shouldBe "Responsible individual declined the terms of use."
        }

        events.collect {
          case stateEvent: ApplicationEvents.ApplicationStateChanged =>
            stateEvent.applicationId shouldBe applicationId
            stateEvent.eventDateTime shouldBe ts
            stateEvent.actor shouldBe Actors.AppCollaborator(adminOne.emailAddress)
            stateEvent.requestingAdminEmail shouldBe requesterEmail
            stateEvent.requestingAdminName shouldBe requesterName
            stateEvent.newAppState shouldBe State.TESTING.toString()
            stateEvent.oldAppState shouldBe State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION.toString()
        }
      }
    }

    def checkSuccessResultTouUplift()(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (returnedApp, events) =>
        events should have size 1

        events.collect {
          case riDeclined: ApplicationEvents.ResponsibleIndividualDeclinedOrDidNotVerify =>
            riDeclined.applicationId shouldBe applicationId
            riDeclined.eventDateTime shouldBe ts
            riDeclined.actor shouldBe Actors.AppCollaborator(adminOne.emailAddress)
            riDeclined.responsibleIndividualName shouldBe riName
            riDeclined.responsibleIndividualEmail shouldBe riEmail
            riDeclined.submissionIndex shouldBe submission.latestInstance.index
            riDeclined.submissionId.value shouldBe submission.id.value
            riDeclined.requestingAdminEmail shouldBe adminOne.emailAddress
            riDeclined.code shouldBe code
        }
      }
    }

    def checkSuccessResultUpdate()(fn: => CommandHandler.AppCmdResultT) = {
      val testMe = await(fn.value).value

      inside(testMe) { case (returnedApp, events) =>
        events should have size 1

        events.collect {
          case riDeclined: ApplicationEvents.ResponsibleIndividualDeclinedUpdate =>
            riDeclined.applicationId shouldBe applicationId
            riDeclined.eventDateTime shouldBe ts
            riDeclined.actor shouldBe Actors.AppCollaborator(adminOne.emailAddress)
            riDeclined.responsibleIndividualName shouldBe newResponsibleIndividual.fullName.value
            riDeclined.responsibleIndividualEmail shouldBe newResponsibleIndividual.emailAddress
            riDeclined.submissionIndex shouldBe submission.latestInstance.index
            riDeclined.submissionId.value shouldBe submission.id.value
            riDeclined.requestingAdminEmail shouldBe adminOne.emailAddress
            riDeclined.code shouldBe code
        }
      }
    }
  }

  "process" should {
    "create correct event for a valid request with a ToU responsibleIndividualVerification and a standard app" in new Setup {
      ApplicationRepoMock.UpdateApplicationState.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      ResponsibleIndividualVerificationRepositoryMock.DeleteSubmissionInstance.succeeds()
      SubmissionsServiceMock.DeclineApprovalRequest.succeeds()
      StateHistoryRepoMock.Insert.succeeds()

      checkSuccessResultToU() {
        underTest.process(app, DeclineResponsibleIndividual(code, instant))
      }
    }

    "create correct event for a valid request with an update responsibleIndividualVerification and a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationUpdate)
      ResponsibleIndividualVerificationRepositoryMock.DeleteSubmissionInstance.succeeds()

      val prodApp = app.withState(ApplicationStateExamples.production(requesterEmail.text, requesterName))

      checkSuccessResultUpdate() {
        underTest.process(prodApp, DeclineResponsibleIndividual(code, instant))
      }
    }

    "create correct event for a valid request with an ToU uplift responsibleIndividualVerification and a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationTouUplift)
      ResponsibleIndividualVerificationRepositoryMock.DeleteSubmissionInstance.succeeds()
      SubmissionsServiceMock.DeclineSubmission.thenReturn(declinedSubmission)
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()

      val prodApp = app.withState(ApplicationStateExamples.production(requesterEmail.text, requesterName))

      checkSuccessResultTouUplift() {
        underTest.process(prodApp, DeclineResponsibleIndividual(code, instant))
      }
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturnNothing

      checkFailsWith(s"No responsibleIndividualVerification found for code $code") {
        underTest.process(app, DeclineResponsibleIndividual(code, instant))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val nonStandardApp = app.withAccess(Access.Ropc(Set.empty))

      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(nonStandardApp, DeclineResponsibleIndividual(code, instant))
      }
    }

    "return an error if the application is old journey" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val oldJourneyApp = app.withAccess(Access.Standard(List.empty, List.empty, None, None, Set.empty, None, None))

      checkFailsWith("Must be a standard new journey application", "The responsible individual has not been set for this application") {
        underTest.process(oldJourneyApp, DeclineResponsibleIndividual(code, instant))
      }
    }

    "return an error if the application is is different between the request and the responsibleIndividualVerification record" in new Setup {
      val riVerification2 = ResponsibleIndividualToUVerification(
        ResponsibleIndividualVerificationId(code),
        ApplicationId.random,
        submission.id,
        submission.latestInstance.index,
        ApplicationName("App Name"),
        instant,
        ResponsibleIndividualVerificationState.INITIAL
      )
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification2)

      checkFailsWith("The given application id is different") {
        underTest.process(app, DeclineResponsibleIndividual(code, instant))
      }
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerificationToU)
      val pendingGKApprovalApp = app.withState(ApplicationStateExamples.pendingGatekeeperApproval(requesterEmail.text, requesterName))

      checkFailsWith("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state") {
        underTest.process(pendingGKApprovalApp, DeclineResponsibleIndividual(code, instant))
      }
    }
  }
}
