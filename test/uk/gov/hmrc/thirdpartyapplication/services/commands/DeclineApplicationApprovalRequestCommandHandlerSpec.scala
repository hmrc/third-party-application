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

import cats.data.NonEmptyChain
import cats.data.Validated.Invalid

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}

class DeclineApplicationApprovalRequestCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId                    = ApplicationId.random
    val appAdminUserId           = UserId.random
    val appAdminEmail            = "admin@example.com"
    val riName                   = "Mr Responsible"
    val riEmail                  = "ri@example.com"
    val newResponsibleIndividual = ResponsibleIndividual.build("New RI", "new-ri@example")
    val oldRiName                = "old ri"
    val requesterEmail           = appAdminEmail
    val requesterName            = "mr admin"
    val gatekeeperUser           = "GateKeeperUser"
    val reasons                  = "reasons description text"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail),
      Set.empty,
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app       = anApplicationData(appId).copy(
      collaborators = Set(
        Collaborator(appAdminEmail, Role.ADMINISTRATOR, appAdminUserId)
      ),
      access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)),
      state = ApplicationState.pendingGatekeeperApproval(requesterEmail, requesterName)
    )
    val ts        = FixedClock.now
    val underTest = new DeclineApplicationApprovalRequestCommandHandler(SubmissionsServiceMock.aMock)
  }

  "process" should {
    "create correct event for a valid request with a submission and a standard app" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)

      val result = await(underTest.process(app, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)))

      result.isValid shouldBe true
      result.toOption.get.length shouldBe 2

      val appApprovalRequestDeclined = result.toOption.get.head.asInstanceOf[ApplicationApprovalRequestDeclined]
      appApprovalRequestDeclined.applicationId shouldBe appId
      appApprovalRequestDeclined.eventDateTime shouldBe ts
      appApprovalRequestDeclined.actor shouldBe GatekeeperUserActor(gatekeeperUser)
      appApprovalRequestDeclined.decliningUserName shouldBe gatekeeperUser
      appApprovalRequestDeclined.decliningUserEmail shouldBe gatekeeperUser
      appApprovalRequestDeclined.submissionIndex shouldBe submittedSubmission.latestInstance.index
      appApprovalRequestDeclined.submissionId shouldBe submittedSubmission.id
      appApprovalRequestDeclined.requestingAdminEmail shouldBe appAdminEmail
      appApprovalRequestDeclined.reasons shouldBe reasons

      val stateEvent = result.toOption.get.tail.head.asInstanceOf[ApplicationStateChanged]
      stateEvent.applicationId shouldBe appId
      stateEvent.eventDateTime shouldBe ts
      stateEvent.actor shouldBe GatekeeperUserActor(gatekeeperUser)
      stateEvent.requestingAdminEmail shouldBe requesterEmail
      stateEvent.requestingAdminName shouldBe requesterName
      stateEvent.newAppState shouldBe State.TESTING
      stateEvent.oldAppState shouldBe State.PENDING_GATEKEEPER_APPROVAL
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone
      val result = await(underTest.process(app, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)))
      result shouldBe Invalid(NonEmptyChain.one(s"No submission found for application ${app.id}"))
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      val result         = await(underTest.process(nonStandardApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application"))
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      val result        = await(underTest.process(oldJourneyApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)))
      result shouldBe Invalid(NonEmptyChain.apply("Must be a standard new journey application"))
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification or PendingGatekeeperApproval" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val pendingGKApprovalApp = app.copy(state = ApplicationState.pendingRequesterVerification(requesterEmail, requesterName, "12345678"))
      val result               = await(underTest.process(pendingGKApprovalApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)))
      result shouldBe Invalid(NonEmptyChain.one("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION or PENDING_GATEKEEPER_APPROVAL state"))
    }
  }
}
