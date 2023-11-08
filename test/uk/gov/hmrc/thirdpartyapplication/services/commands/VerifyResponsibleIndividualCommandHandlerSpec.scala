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

import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.VerifyResponsibleIndividual
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.ResponsibleIndividualVerificationStarted
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access

class VerifyResponsibleIndividualCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule with ResponsibleIndividualVerificationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val submission     = aSubmission
    val appAdminUserId = UserId.random
    val appAdminEmail  = "admin@example.com".toLaxEmail
    val appAdminName   = "Ms Admin"
    val oldRiUserId    = UserId.random
    val oldRiEmail     = "oldri@example.com".toLaxEmail
    val oldRiName      = "old ri"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(oldRiName, oldRiEmail.text),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val app = anApplicationData(applicationId).copy(
      collaborators = Set(
        appAdminEmail.admin(appAdminUserId),
        oldRiEmail.admin(oldRiUserId)
      ),
      access = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )

    val ts      = FixedClock.instant
    val riName  = "Mr Responsible"
    val riEmail = "ri@example.com".toLaxEmail

    val underTest = new VerifyResponsibleIndividualCommandHandler(SubmissionsServiceMock.aMock, ResponsibleIndividualVerificationRepositoryMock.aMock)

  }

  "process" should {
    "create correct event for a valid request with a standard app" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()

      val result = await(underTest.process(app, VerifyResponsibleIndividual(appAdminUserId, now, appAdminName, riName, riEmail)).value).value

      inside(result) { case (app, events) =>
        events should have size 1

        inside(events.head) {
          case event: ResponsibleIndividualVerificationStarted =>
            event.applicationId shouldBe applicationId
            event.applicationName shouldBe app.name
            event.eventDateTime shouldBe ts
            event.actor shouldBe Actors.AppCollaborator(appAdminEmail)
            event.responsibleIndividualName shouldBe riName
            event.responsibleIndividualEmail shouldBe riEmail
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
        underTest.process(app, VerifyResponsibleIndividual(appAdminUserId, now, appAdminName, riName, riEmail))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val nonStandardApp = app.copy(access = Access.Ropc(Set.empty))

      checkFailsWith("Must be a standard new journey application") {
        underTest.process(nonStandardApp, VerifyResponsibleIndividual(appAdminUserId, now, appAdminName, riName, riEmail))
      }
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val oldJourneyApp = app.copy(access = Access.Standard(List.empty, None, None, Set.empty, None, None))

      checkFailsWith("Must be a standard new journey application") {
        underTest.process(oldJourneyApp, VerifyResponsibleIndividual(appAdminUserId, now, appAdminName, riName, riEmail))
      }
    }

    "return an error if the application is not approved" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val notApprovedApp = app.copy(state = ApplicationStateExamples.pendingGatekeeperApproval("someone@example.com", "Someone"))

      checkFailsWith("App is not in PRE_PRODUCTION or in PRODUCTION state") {
        underTest.process(notApprovedApp, VerifyResponsibleIndividual(appAdminUserId, now, appAdminName, riName, riEmail))
      }
    }

    "return an error if the requester is not an admin for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      checkFailsWith("User must be an ADMIN") {
        underTest.process(app, VerifyResponsibleIndividual(UserId.random, now, appAdminName, riName, riEmail))
      }
    }

    "return an error if the requester is already the RI for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      checkFailsWith(s"The specified individual is already the RI for this application") {
        underTest.process(app, VerifyResponsibleIndividual(oldRiUserId, now, appAdminName, oldRiName, oldRiEmail))
      }
    }
  }
}
