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

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule}
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.TermsAndConditionsLocations
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.PrivacyPolicyLocations

class DeclineApplicationApprovalRequestCommandHandlerSpec extends AsyncHmrcSpec
    with ApplicationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with ApplicationTestData
    with SubmissionsTestData {

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
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val applicationData = anApplicationData(appId).copy(
      collaborators = Set(
        Collaborator(appAdminEmail, Role.ADMINISTRATOR, appAdminUserId)
      ),
      access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)),
      state = ApplicationState.pendingGatekeeperApproval(requesterEmail, requesterName)
    )
    val ts              = FixedClock.now
    val underTest       = new DeclineApplicationApprovalRequestCommandHandler(ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, SubmissionsServiceMock.aMock)

    def checkSuccessResult()(result: CommandHandler.CommandSuccess) = {
      inside(result) { case (app, events) =>
        val filteredEvents = events.toList.filter(evt =>
          evt match {
            case _: ApplicationStateChanged | _: ApplicationApprovalRequestDeclined => true
            case _                                                                  => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ApplicationApprovalRequestDeclined(
                  _,
                  appId,
                  eventDateTime,
                  actor,
                  decliningUserName,
                  decliningUserEmail,
                  submissionId,
                  submissionIndex,
                  evtReasons,
                  requestingAdminName,
                  requestingAdminEmail
                ) =>
              appId shouldBe app.id
              actor shouldBe Actors.GatekeeperUser(gatekeeperUser)
              eventDateTime shouldBe ts
              decliningUserName shouldBe gatekeeperUser
              decliningUserEmail shouldBe gatekeeperUser
              submissionId shouldBe submittedSubmission.id
              submissionIndex shouldBe submittedSubmission.latestInstance.index
              evtReasons shouldBe reasons
              requestingAdminEmail shouldBe requesterEmail
              requestingAdminName shouldBe requesterName

            case ApplicationStateChanged(_, appId, eventDateTime, evtActor, oldAppState, newAppState, requestingAdminName, requestingAdminEmail) =>
              appId shouldBe app.id
              evtActor shouldBe Actors.GatekeeperUser(gatekeeperUser)
              eventDateTime shouldBe ts
              oldAppState shouldBe app.state.name
              newAppState shouldBe State.TESTING
              requestingAdminEmail shouldBe requesterEmail
              requestingAdminName shouldBe requesterName
          }
        )
      }
    }

  }

  "DeclineApplicationApprovalRequest" should {

    "succeed as gkUserActor" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      SubmissionsServiceMock.DeclineApprovalRequest.succeeds()
      ApplicationRepoMock.UpdateApplicationState.thenReturn(applicationData.copy(state = testingState()))
      StateHistoryRepoMock.ApplyEvents.succeeds()

      val result = await(underTest.process(applicationData, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)).value).right.value

      checkSuccessResult()(result)
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone
      val result = await(underTest.process(applicationData, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)).value).left.value.toNonEmptyList.toList
      result.head shouldBe s"No submission found for application ${applicationData.id.value}"
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val nonStandardApp = applicationData.copy(access = Ropc(Set.empty))
      val result         = await(underTest.process(nonStandardApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)).value).left.value.toNonEmptyList.toList
      result.head shouldBe "Must be a standard new journey application"
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val oldJourneyApp = applicationData.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      val result        = await(underTest.process(oldJourneyApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)).value).left.value.toNonEmptyList.toList
      result.head shouldBe "Must be a standard new journey application"
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification or PendingGatekeeperApproval" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val pendingGKApprovalApp = applicationData.copy(state = ApplicationState.pendingRequesterVerification(requesterEmail, requesterName, "12345678"))
      val result               = await(underTest.process(pendingGKApprovalApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, ts)).value).left.value.toNonEmptyList.toList
      result.head shouldBe "App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION or PENDING_GATEKEEPER_APPROVAL state"
    }

  }

}
