/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.data.NonEmptyChain
import cats.data.Validated.Invalid
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class VerifyResponsibleIndividualCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId = ApplicationId.random
    val submission = aSubmission
    val appAdminUserId = UserId.random
    val appAdminEmail = "admin@example.com"
    val appAdminName = "Ms Admin"
    val oldRiUserId = UserId.random
    val oldRiEmail = "oldri@example.com"
    val oldRiName = "old ri"
    val importantSubmissionData = ImportantSubmissionData(None, ResponsibleIndividual.build(oldRiName, oldRiEmail),
      Set.empty, TermsAndConditionsLocation.InDesktopSoftware, PrivacyPolicyLocation.InDesktopSoftware, List.empty)
    val app = anApplicationData(appId).copy(collaborators = Set(
      Collaborator(appAdminEmail, Role.ADMINISTRATOR, appAdminUserId),
      Collaborator(oldRiEmail, Role.ADMINISTRATOR, oldRiUserId)
    ), access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)))
    val ts = LocalDateTime.now
    val riName = "Mr Responsible"
    val riEmail = "ri@example.com"
    val underTest = new VerifyResponsibleIndividualCommandHandler(SubmissionsServiceMock.aMock)
  }

  "process" should {
    "create correct event for a valid request with a standard app" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, VerifyResponsibleIndividual(appAdminUserId, ts, appAdminName, riName, riEmail)))
      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ResponsibleIndividualVerificationStarted]
      event.applicationId shouldBe appId
      event.applicationName shouldBe app.name
      event.eventDateTime shouldBe ts
      event.actor shouldBe CollaboratorActor(appAdminEmail)
      event.responsibleIndividualName shouldBe riName
      event.responsibleIndividualEmail shouldBe riEmail
      event.submissionIndex shouldBe submission.latestInstance.index
      event.submissionId shouldBe submission.id
      event.requestingAdminEmail shouldBe appAdminEmail
      event.requestingAdminName shouldBe appAdminName
    }

    "return an error if no submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      val result = await(underTest.process(app, VerifyResponsibleIndividual(appAdminUserId, ts, appAdminName, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one(s"No submission found for application $appId"))
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      val result = await(underTest.process(nonStandardApp, VerifyResponsibleIndividual(appAdminUserId, ts, appAdminName, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one("Must be a standard new journey application"))
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      val result = await(underTest.process(oldJourneyApp, VerifyResponsibleIndividual(appAdminUserId, ts, appAdminName, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one("Must be a standard new journey application"))
    }

    "return an error if the application is not approved" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val notApprovedApp = app.copy(state = ApplicationState.pendingGatekeeperApproval("someone@example.com"))
      val result = await(underTest.process(notApprovedApp, VerifyResponsibleIndividual(appAdminUserId, ts, appAdminName, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one("App is not in PRE_PRODUCTION or in PRODUCTION state"))
    }

    "return an error if the requester is not an admin for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, VerifyResponsibleIndividual(UserId.random, ts, appAdminName, riName, riEmail)))
      result shouldBe Invalid(NonEmptyChain.one("User must be an ADMIN"))
    }

    "return an error if the requester is already the RI for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, VerifyResponsibleIndividual(oldRiUserId, ts, appAdminName, oldRiName, oldRiEmail)))
      result shouldBe Invalid(NonEmptyChain.one(s"The specified individual is already the RI for this application"))
    }
  }
}