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

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

import cats.data.NonEmptyChain
import cats.data.Validated.Invalid

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class ChangeResponsibleIndividualToSelfCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId          = ApplicationId.random
    val submission     = aSubmission
    val appAdminUserId = UserId.random
    val appAdminEmail  = "admin@example.com"
    val oldRiUserId    = UserId.random
    val oldRiEmail     = "oldri@example.com"
    val oldRiName      = "old ri"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(oldRiName, oldRiEmail),
      Set.empty,
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app       = anApplicationData(appId).copy(
      collaborators = Set(
        Collaborator(appAdminEmail, Role.ADMINISTRATOR, appAdminUserId),
        Collaborator(oldRiEmail, Role.ADMINISTRATOR, oldRiUserId)
      ),
      access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    )
    val ts        = LocalDateTime.now
    val riName    = "Mr Responsible"
    val riEmail   = "ri@example.com"
    val underTest = new ChangeResponsibleIndividualToSelfCommandHandler(SubmissionsServiceMock.aMock)
  }

  "process" should {
    "create correct event for a valid request with a standard app" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)))
      result.isValid shouldBe true
      val event  = result.toOption.get.head.asInstanceOf[ResponsibleIndividualChangedToSelf]
      event.applicationId shouldBe appId
      event.eventDateTime shouldBe ts
      event.actor shouldBe CollaboratorActor(appAdminEmail)
      event.previousResponsibleIndividualName shouldBe oldRiName
      event.previousResponsibleIndividualEmail shouldBe oldRiEmail
      event.submissionIndex shouldBe submission.latestInstance.index
      event.submissionId shouldBe submission.id
      event.requestingAdminName shouldBe riName
      event.requestingAdminEmail shouldBe appAdminEmail
    }

    "return an error if no submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      val result = await(underTest.process(app, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one(s"No submission found for application $appId"))
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      val result         = await(underTest.process(nonStandardApp, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application"))
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      val result        = await(underTest.process(oldJourneyApp, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application"))
    }

    "return an error if the application is not approved" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val notApprovedApp = app.copy(state = ApplicationState.pendingGatekeeperApproval("someone@example.com", "Someone"))
      val result         = await(underTest.process(notApprovedApp, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one("App is not in PRE_PRODUCTION or in PRODUCTION state"))
    }

    "return an error if the requester is not an admin for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, ChangeResponsibleIndividualToSelf(UserId.random, ts, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }

    "return an error if the requester is already the RI for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, ChangeResponsibleIndividualToSelf(oldRiUserId, ts, oldRiName, oldRiEmail)))
      result shouldBe Invalid(NonEmptyChain.one(s"The specified individual is already the RI for this application"))
    }
  }
}
