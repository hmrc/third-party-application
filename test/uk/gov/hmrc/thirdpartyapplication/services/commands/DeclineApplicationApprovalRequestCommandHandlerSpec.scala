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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, PrivacyPolicyLocations, TermsAndConditionsLocations}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeclineApplicationApprovalRequest
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{ApplicationRepositoryMockModule, StateHistoryRepositoryMockModule}

class DeclineApplicationApprovalRequestCommandHandlerSpec extends CommandHandlerBaseSpec with SubmissionsTestData {

  trait Setup extends ApplicationRepositoryMockModule with StateHistoryRepositoryMockModule with SubmissionsServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId                    = ApplicationId.random
    val appAdminUserId           = UserId.random
    val appAdminEmail            = "admin@example.com".toLaxEmail
    val riName                   = "Mr Responsible"
    val riEmail                  = "ri@example.com".toLaxEmail
    val newResponsibleIndividual = ResponsibleIndividual.build("New RI", "new-ri@example")
    val oldRiName                = "old ri"
    val requesterEmail           = appAdminEmail
    val requesterName            = "mr admin"
    val gatekeeperUser           = "GateKeeperUser"
    val reasons                  = "reasons description text"

    val importantSubmissionData = ImportantSubmissionData(
      None,
      ResponsibleIndividual.build(riName, riEmail.text),
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List.empty
    )

    val applicationData = anApplicationData(appId).copy(
      collaborators = Set(
        appAdminEmail.admin(appAdminUserId)
      ),
      access = Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData)),
      state = ApplicationState.pendingGatekeeperApproval(requesterEmail.text, requesterName)
    )
    val ts              = FixedClock.instant
    val underTest       = new DeclineApplicationApprovalRequestCommandHandler(ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, SubmissionsServiceMock.aMock)

    def checkSuccessResult()(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        val filteredEvents = events.toList.filter(evt =>
          evt match {
            case _: ApplicationEvents.ApplicationStateChanged | _: ApplicationEvents.ApplicationApprovalRequestDeclined => true
            case _                                                                                                      => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ApplicationEvents.ApplicationApprovalRequestDeclined(
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
              decliningUserEmail shouldBe gatekeeperUser.toLaxEmail
              submissionId.value shouldBe submittedSubmission.id.value
              submissionIndex shouldBe submittedSubmission.latestInstance.index
              evtReasons shouldBe reasons
              requestingAdminEmail shouldBe requesterEmail
              requestingAdminName shouldBe requesterName

            case ApplicationEvents.ApplicationStateChanged(_, appId, eventDateTime, evtActor, oldAppState, newAppState, requestingAdminName, requestingAdminEmail) =>
              appId shouldBe app.id
              evtActor shouldBe Actors.GatekeeperUser(gatekeeperUser)
              eventDateTime shouldBe ts
              oldAppState shouldBe applicationData.state.name.toString()
              newAppState shouldBe State.TESTING.toString()
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
      StateHistoryRepoMock.Insert.succeeds()

      val result = await(underTest.process(applicationData, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, now)).value).value

      checkSuccessResult()(result)
    }

    "return an error if no responsibleIndividualVerification is found for the code" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      checkFailsWith(s"No submission found for application ${applicationData.id.value}") {
        underTest.process(applicationData, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, now))
      }
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val nonStandardApp = applicationData.copy(access = Ropc(Set.empty))
      checkFailsWith("Must be a standard new journey application") {
        underTest.process(nonStandardApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, now))
      }
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val oldJourneyApp = applicationData.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      checkFailsWith("Must be a standard new journey application") {
        underTest.process(oldJourneyApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, now))
      }
    }

    "return an error if the application state is not PendingResponsibleIndividualVerification or PendingGatekeeperApproval" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submittedSubmission)
      val pendingGKApprovalApp = applicationData.copy(state = ApplicationState.pendingRequesterVerification(requesterEmail.text, requesterName, "12345678"))
      checkFailsWith("App is not in PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION or PENDING_GATEKEEPER_APPROVAL state") {
        underTest.process(pendingGKApprovalApp, DeclineApplicationApprovalRequest(gatekeeperUser, reasons, now))
      }
    }

  }

}
