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

package uk.gov.hmrc.apiplatform.modules.uplift.services

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationStateFixtures, State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.uplift.domain.models.InvalidUpliftVerificationCode
import uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.repositories.UpliftLinksRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, UpliftVerified}
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class UpliftServiceSpec extends AsyncHmrcSpec with ApplicationStateFixtures {

  trait Setup
      extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with UpliftServiceMockModule
      with UpliftNamingServiceMockModule
      with UpliftLinksRepositoryMockModule
      with ApiGatewayStoreMockModule
      with CommonApplicationId
      with StoredApplicationFixtures {

    val mockAppNamingService: UpliftNamingService = mock[UpliftNamingService]

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val underTest: UpliftService =
      new UpliftService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, UpliftNamingServiceMock.aMock, ApiGatewayStoreMock.aMock, clock)
  }

  "verifyUplift" should {
    val upliftRequestedBy = appStateRequestByEmail.toLaxEmail

    "update the state of the application and create app in the API gateway when application is in pendingRequesterVerification state" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenReturn(mock[StoredApplication])

      val upliftWasRequested             = StateHistory(applicationId, State.PENDING_GATEKEEPER_APPROVAL, Actors.AppCollaborator(upliftRequestedBy), Some(State.TESTING), changedAt = instant)
      val application: StoredApplication = storedApp.withState(appStatePendingRequesterVerification)

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(appStateVerificationCode)(application)

      StateHistoryRepoMock.FetchLatestByStateForApplication.thenReturnWhen(applicationId, State.PENDING_GATEKEEPER_APPROVAL)(upliftWasRequested)
      StateHistoryRepoMock.Insert.thenAnswer()

      val result: ApplicationStateChange = await(underTest.verifyUplift(appStateVerificationCode))

      val expectedApplication: StoredApplication = application.withState(appStatePreProduction)
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplication)
      ApiGatewayStoreMock.CreateApplication.verifyCalled()

      result shouldBe UpliftVerified

      val expectedStateHistory =
        StateHistory(applicationId, State.PRE_PRODUCTION, Actors.AppCollaborator(upliftRequestedBy), Some(State.PENDING_REQUESTER_VERIFICATION), changedAt = instant)
      StateHistoryRepoMock.Insert.verifyCalledWith(expectedStateHistory)
    }

    "fail if the application save fails" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      val application: StoredApplication = storedApp.withState(appStatePendingRequesterVerification)
      val saveException                  = new RuntimeException("application failed to save")

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(appStateVerificationCode)(application)
      ApplicationRepoMock.Save.thenFail(saveException)

      intercept[RuntimeException] {
        await(underTest.verifyUplift(appStateVerificationCode))
      }
    }

    "rollback if saving the state history fails" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      val application: StoredApplication = storedApp.withState(appStatePendingRequesterVerification)
      ApplicationRepoMock.Save.thenReturn(mock[StoredApplication])
      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(appStateVerificationCode)(application)
      StateHistoryRepoMock.Insert.thenFailsWith(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.verifyUplift(appStateVerificationCode))
      }

      ApplicationRepoMock.Save.verifyCalledWith(application)
    }

    "not update the state but result in success of the application when application is already in production state" in new Setup {
      val application: StoredApplication = storedApp.withState(appStateProduction)

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(appStateVerificationCode)(application)

      val result: ApplicationStateChange = await(underTest.verifyUplift(appStateVerificationCode))
      result shouldBe UpliftVerified
      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "fail when application is in testing state" in new Setup {
      val application: StoredApplication = storedApp.withState(appStateTesting)

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(appStateVerificationCode)(application)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(appStateVerificationCode))
      }
    }

    "fail when application is in pendingGatekeeperApproval state" in new Setup {
      val application: StoredApplication = storedApp.withState(appStatePendingGatekeeperApproval)

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(appStateVerificationCode)(application)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(appStateVerificationCode))
      }
    }

    "fail when application is not found by verification code" in new Setup {
      storedApp.withState(appStatePendingGatekeeperApproval)

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnNoneWhen(appStateVerificationCode)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(appStateVerificationCode))
      }
    }
  }
}
