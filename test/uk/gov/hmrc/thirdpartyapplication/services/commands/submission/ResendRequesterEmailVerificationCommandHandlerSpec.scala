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
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationState, State}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ResendRequesterEmailVerification
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.RequesterEmailVerificationResent
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class ResendRequesterEmailVerificationCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission     = aSubmission
    val appAdminUserId = UserId.random
    val appAdminEmail  = "admin@example.com".toLaxEmail
    val appAdminName   = "Ms Admin"
    val gatekeeperUser = "GateKeeperUser"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build("Bob", "bob@example.com"),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app = anApplicationData.copy(
      state = ApplicationStateExamples.pendingRequesterVerification(appAdminEmail.text, appAdminName, "123456789"),
      access = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )

    val ts = FixedClock.instant

    val underTest = new ResendRequesterEmailVerificationCommandHandler(SubmissionsServiceMock.aMock)
  }

  "process" should {
    "create correct event for a valid request with a standard app" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val result = await(underTest.process(app, ResendRequesterEmailVerification(gatekeeperUser, instant)).value).value

      inside(result) { case (app, events) =>
        events should have size 1

        inside(events.head) {
          case event: RequesterEmailVerificationResent =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gatekeeperUser)
            event.submissionIndex shouldBe submission.latestInstance.index
            event.submissionId.value shouldBe submission.id.value
            event.requestingAdminEmail shouldBe appAdminEmail
            event.requestingAdminName shouldBe appAdminName
        }
      }
    }

    "return an error if no submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()

      checkFailsWith(s"No submission found for application $applicationId") {
        underTest.process(app, ResendRequesterEmailVerification(gatekeeperUser, instant))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val nonStandardApp = app.copy(access = Access.Ropc(Set.empty))

      checkFailsWith("Must be a standard new journey application") {
        underTest.process(nonStandardApp, ResendRequesterEmailVerification(gatekeeperUser, instant))
      }
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val oldJourneyApp = app.copy(access = Access.Standard(List.empty, None, None, Set.empty, None, None))

      checkFailsWith("Must be a standard new journey application") {
        underTest.process(oldJourneyApp, ResendRequesterEmailVerification(gatekeeperUser, instant))
      }
    }

    "return an error if the application has no verification code" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val noVerificationCodeApp = app.copy(state = ApplicationState(State.PENDING_REQUESTER_VERIFICATION, Some(appAdminEmail.text), Some(appAdminName), None, instant))

      checkFailsWith("The verificationCode has not been set for this application") {
        underTest.process(noVerificationCodeApp, ResendRequesterEmailVerification(gatekeeperUser, instant))
      }
    }

    "return an error if the application is not pending requester verification" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val notApprovedApp = app.copy(state = ApplicationStateExamples.pendingGatekeeperApproval("someone@example.com", "Someone"))

      checkFailsWith("App is not in PENDING_REQUESTER_VERIFICATION state", "The verificationCode has not been set for this application") {
        underTest.process(notApprovedApp, ResendRequesterEmailVerification(gatekeeperUser, instant))
      }
    }
  }
}
