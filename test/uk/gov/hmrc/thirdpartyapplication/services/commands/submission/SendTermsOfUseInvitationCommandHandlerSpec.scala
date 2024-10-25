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

import java.time.temporal.ChronoUnit._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SendTermsOfUseInvitation
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.TermsOfUseInvitationSent
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule, TermsOfUseInvitationRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.services.TermsOfUseInvitationConfig
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandlerBaseSpec

class SendTermsOfUseInvitationCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with TermsOfUseInvitationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appAdminUserId = UserId.random
    val appAdminEmail  = "bob@example.com".toLaxEmail
    val appAdminName   = "Bob"

    val app = anApplicationData().copy(
      state = ApplicationStateExamples.production(appAdminEmail.text, appAdminName)
    )

    val daysUntilDueWhenCreated = FiniteDuration(21, java.util.concurrent.TimeUnit.DAYS)
    val ts                      = FixedClock.instant
    val dueBy                   = ts.plus(daysUntilDueWhenCreated.toMinutes, MINUTES)
    val invite                  = TermsOfUseInvitation(app.id, ts, ts, dueBy, None, EMAIL_SENT)

    val underTest = new SendTermsOfUseInvitationCommandHandler(
      ApplicationRepoMock.aMock,
      TermsOfUseInvitationRepositoryMock.aMock,
      SubmissionsServiceMock.aMock,
      TermsOfUseInvitationConfig(daysUntilDueWhenCreated, FiniteDuration(30, java.util.concurrent.TimeUnit.DAYS)),
      FixedClock.clock
    )
  }

  "process" should {
    "create correct event for a valid request" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturnNone()
      TermsOfUseInvitationRepositoryMock.Create.thenReturnSuccess(invite)

      val result = await(underTest.process(app, SendTermsOfUseInvitation(gkUserEmail, ts)).value).value

      inside(result) { case (_, events) =>
        events should have size 1

        inside(events.head) {
          case event: TermsOfUseInvitationSent =>
            event.applicationId shouldBe applicationId
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.GatekeeperUser(gkUserEmail)
            event.dueBy shouldBe dueBy
        }
      }
    }

    "return an error if an existing submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(aSubmission)
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturnNone()

      checkFailsWith(s"Submission already exists for application $applicationId") {
        underTest.process(app, SendTermsOfUseInvitation(gkUserEmail, ts))
      }
    }

    "return an error if an existing terms of use invitiation is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(invite)

      checkFailsWith(s"Terms of Use Invitation already exists for application $applicationId") {
        underTest.process(app, SendTermsOfUseInvitation(gkUserEmail, ts))
      }
    }

    "return an error if the application is not in PRODUCTION" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturnNone()

      val testingApp = app.copy(state = ApplicationStateExamples.testing)

      checkFailsWith("App is not in PRODUCTION state") {
        underTest.process(testingApp, SendTermsOfUseInvitation(gkUserEmail, ts))
      }

    }
  }
}
