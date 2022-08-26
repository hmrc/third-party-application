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
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualToUVerification
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState

class ChangeResponsibleIndividualToOtherCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup extends ResponsibleIndividualVerificationRepositoryMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId = ApplicationId.random
    val submission = aSubmission
    val appAdminUserId = UserId.random
    val appAdminEmail = "admin@example.com"
    val riName = "Mr Responsible"
    val riEmail = "ri@example.com"
    val oldRiName = "old ri"
    val requesterEmail = appAdminEmail
    val importantSubmissionData = ImportantSubmissionData(None, ResponsibleIndividual.build(riName, riEmail),
      Set.empty, TermsAndConditionsLocation.InDesktopSoftware, PrivacyPolicyLocation.InDesktopSoftware, List.empty)
    val app = anApplicationData(appId).copy(collaborators = Set(
      Collaborator(appAdminEmail, Role.ADMINISTRATOR, appAdminUserId)
    ), access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)
    ), state = ApplicationState.pendingResponsibleIndividualVerification(requesterEmail))
    val ts = LocalDateTime.now
    val code = "3242342387452384623549234"
    val riVerification = ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId(code), 
      appId, submission.id, submission.latestInstance.index, "App Name", ts, ResponsibleIndividualVerificationState.INITIAL)  
    val underTest = new ChangeResponsibleIndividualToOtherCommandHandler(ResponsibleIndividualVerificationRepositoryMock.aMock)
  }

  "process" should {
    "create correct event for a valid request with a standard app" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification)
      val result = await(underTest.process(app, ChangeResponsibleIndividualToOther(code, ts)))
      result.isValid shouldBe true
      val event = result.toOption.get.head.asInstanceOf[ResponsibleIndividualSet]
      event.applicationId shouldBe appId
      event.eventDateTime shouldBe ts
      event.actor shouldBe CollaboratorActor(appAdminEmail)
      event.responsibleIndividualName shouldBe riName
      event.responsibleIndividualEmail shouldBe riEmail
      event.submissionIndex shouldBe submission.latestInstance.index
      event.submissionId shouldBe submission.id
      event.requestingAdminEmail shouldBe appAdminEmail
      event.code shouldBe code
      event.newAppState shouldBe State.PENDING_GATEKEEPER_APPROVAL
      event.oldAppState shouldBe State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturnNothing
      val result = await(underTest.process(app, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.one(s"No responsibleIndividualVerification found for code $code"))
    }

    "return an error if the application is non-standard" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      val result = await(underTest.process(nonStandardApp, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application"))
    }

    "return an error if the application is old journey" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      val result = await(underTest.process(oldJourneyApp, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application"))
    }

    "return an error if the application is is different between the request and the responsibleIndividualVerification record" in new Setup {
      val riVerification2 = ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId(code), 
        ApplicationId.random, submission.id, submission.latestInstance.index, "App Name", ts, ResponsibleIndividualVerificationState.INITIAL)  
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification2)
      val result = await(underTest.process(app, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.one("The given application id is different"))
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.Fetch.thenReturn(riVerification)
      val pendingGKApprovalApp = app.copy(state = ApplicationState.pendingGatekeeperApproval(requesterEmail))
      val result = await(underTest.process(pendingGKApprovalApp, ChangeResponsibleIndividualToOther(code, ts)))
      result shouldBe Invalid(NonEmptyChain.one("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION state"))
    }
  }
}
