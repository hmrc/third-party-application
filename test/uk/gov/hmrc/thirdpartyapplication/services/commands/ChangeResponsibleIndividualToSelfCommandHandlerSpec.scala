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

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors}

class ChangeResponsibleIndividualToSelfCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup extends SubmissionsServiceMockModule with ApplicationRepositoryMockModule {

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
    val ts        = FixedClock.now
    val riName    = "Mr Responsible"
    val riEmail   = "ri@example.com"
    val underTest = new ChangeResponsibleIndividualToSelfCommandHandler(ApplicationRepoMock.aMock, SubmissionsServiceMock.aMock)

    val changeResponsibleIndividualToSelfCommand = ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)

    def checkSuccessResult(expectedActor: Actor, expectedPreviousEmail: String, expectedPreviousName: String)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).right.value

      inside(testThis) { case (app, events) =>
        events should have size 1
        val event = events.head

        inside(event) {
          case ResponsibleIndividualChangedToSelf(
                _,
                applicationId,
                eventDateTime,
                actor,
                previousResponsibleIndividualName,
                previousResponsibleIndividualEmail,
                submissionId,
                submissionIndex,
                requestingName,
                requestingEmail
              ) =>
            applicationId shouldBe appId
            eventDateTime shouldBe ts
            actor shouldBe Actors.Collaborator(appAdminEmail)
            previousResponsibleIndividualName shouldBe oldRiName
            previousResponsibleIndividualEmail shouldBe oldRiEmail
            submissionIndex shouldBe submission.latestInstance.index
            submissionId shouldBe submission.id
            requestingName shouldBe riName
            requestingEmail shouldBe appAdminEmail
        }
      }
    }

    def checkFailsWith(msg: String)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1
      testThis.head shouldBe msg
    }

  }

  "given a ChangeResponsibleIndividualToSelf command" should {
    "should be processed successfully" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      ApplicationRepoMock.UpdateApplicationChangeResponsibleIndividualToSelf.thenReturn(app) // Not modified

      checkSuccessResult(Actors.Collaborator(adminEmail), oldRiName, oldRiEmail)(underTest.process(app, changeResponsibleIndividualToSelfCommand))
    }

    "return an error if no submission is found for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturnNone()
      val result = await(underTest.process(app, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)).value).left.value
      result.head shouldBe s"No submission found for application ${app.id.value}"
    }

    "return an error if the application is non-standard" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      val result         = await(underTest.process(nonStandardApp, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)).value).left.value
      result shouldBe NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application")
    }

    "return an error if the application is old journey" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val oldJourneyApp = app.copy(access = Standard(List.empty, None, None, Set.empty, None, None))
      val result        = await(underTest.process(oldJourneyApp, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)).value).left.value
      result shouldBe NonEmptyChain.apply("Must be a standard new journey application", "The responsible individual has not been set for this application")
    }

    "return an error if the application is not approved" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val notApprovedApp = app.copy(state = ApplicationState.pendingGatekeeperApproval("someone@example.com", "Someone"))
      val result         = await(underTest.process(notApprovedApp, ChangeResponsibleIndividualToSelf(appAdminUserId, ts, riName, riEmail)).value).left.value
      result.head shouldBe "App is not in PRE_PRODUCTION or in PRODUCTION state"
    }

    "return an error if the requester is not an admin for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, ChangeResponsibleIndividualToSelf(UserId.random, ts, riName, riEmail)).value).left.value
      result.head shouldBe "User must be an ADMIN"
    }

    "return an error if the requester is already the RI for the application" in new Setup {
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)
      val result = await(underTest.process(app, ChangeResponsibleIndividualToSelf(oldRiUserId, ts, oldRiName, oldRiEmail)).value).left.value
      result.head shouldBe s"The specified individual is already the RI for this application"
    }

  }
}
