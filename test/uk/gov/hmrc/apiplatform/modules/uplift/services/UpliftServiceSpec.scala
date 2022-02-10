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

package uk.gov.hmrc.apiplatform.modules.uplift.services

import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.apiplatform.modules.uplift.domain.models.InvalidUpliftVerificationCode
import uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.repositories.UpliftLinksRepositoryMockModule

class UpliftServiceSpec extends AsyncHmrcSpec with LockDownDateTime {
  trait Setup 
    extends AuditServiceMockModule
    with ApplicationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with UpliftServiceMockModule
    with UpliftNamingServiceMockModule
    with UpliftLinksRepositoryMockModule
    with ApiGatewayStoreMockModule
    with ApplicationTestData {

    val applicationId: ApplicationId = ApplicationId.random

    val mockAppNamingService = mock[UpliftNamingService]

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
        
    val underTest = new UpliftService(AuditServiceMock.aMock, ApplicationRepoMock.aMock, StateHistoryRepoMock.aMock, UpliftLinksRepositoryMock.aMock, UpliftNamingServiceMock.aMock, ApiGatewayStoreMock.aMock)
  }

  "requestUplift" should {
    val requestedName = "application name"
    val upliftRequestedBy = "email@example.com"

    "update the state of the application" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()
      ApplicationRepoMock.Save.thenAnswer(successful)

      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val expectedApplication: ApplicationData = application.copy(state = pendingGatekeeperApprovalState(upliftRequestedBy),
        name = requestedName, normalisedName = requestedName.toLowerCase)

      val expectedStateHistory = StateHistory(applicationId = expectedApplication.id, state = PENDING_GATEKEEPER_APPROVAL,
        actor = Actor(upliftRequestedBy, COLLABORATOR), previousState = Some(TESTING))

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.FetchByName.thenReturnWhen(requestedName)(application,expectedApplication)

      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenSucceeds()
      StateHistoryRepoMock.Insert.thenAnswer()

      val result: ApplicationStateChange = await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))

      ApplicationRepoMock.Save.verifyCalledWith(expectedApplication)
      StateHistoryRepoMock.Insert.verifyCalledWith(expectedStateHistory)
      result shouldBe UpliftRequested
    }

    "rollback the application when storing the state history fails" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApplicationRepoMock.FetchByName.thenReturnWhen(requestedName)(application)
      StateHistoryRepoMock.Insert.thenFailsWith(new RuntimeException("Expected test failure"))
      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenSucceeds()
      
      intercept[RuntimeException] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }

      ApplicationRepoMock.Save.verifyCalledWith(application)
    }

    "send an Audit event when an application uplift is successfully requested with no name change" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApplicationRepoMock.FetchByName.thenReturnEmptyList()
      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenSucceeds()
      StateHistoryRepoMock.Insert.thenAnswer()

      await(underTest.requestUplift(applicationId, application.name, upliftRequestedBy))
      AuditServiceMock.Audit.verifyCalledWith(ApplicationUpliftRequested, Map("applicationId" -> application.id.value.toString), hc)
    }

    "send an Audit event when an application uplift is successfully requested with a name change" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())
      
      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.Save.thenAnswer(successful)
      ApplicationRepoMock.FetchByName.thenReturnEmptyWhen(requestedName)
      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenSucceeds()
      StateHistoryRepoMock.Insert.thenAnswer()

      await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))

      val expectedAuditDetails: Map[String, String] = Map("applicationId" -> application.id.value.toString, "newApplicationName" -> requestedName)
      AuditServiceMock.Audit.verifyCalledWith(ApplicationUpliftRequested, expectedAuditDetails, hc)
    }

    "fail with InvalidStateTransition without invoking fetchNonTestingApplicationByName when the application is not in testing" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState("test@example.com"))

      ApplicationRepoMock.Fetch.thenReturn(application)

      intercept[InvalidStateTransition] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
      ApplicationRepoMock.FetchByName.veryNeverCalled()
    }

    "fail with ApplicationAlreadyExists when another uplifted application already exist with the same name" in new Setup {
      AuditServiceMock.Audit.thenReturnSuccess()

      val application: ApplicationData = anApplicationData(applicationId, testingState())
      val anotherApplication: ApplicationData = anApplicationData(ApplicationId.random, productionState("admin@example.com"))

      ApplicationRepoMock.Fetch.thenReturn(application)
      ApplicationRepoMock.FetchByName.thenReturnWhen(requestedName)(application,anotherApplication)
      UpliftNamingServiceMock.AssertAppHasUniqueNameAndAudit.thenFailsWithApplicationAlreadyExists()

      intercept[ApplicationAlreadyExists] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
    }

    "propagate the exception when the repository fail" in new Setup {
      ApplicationRepoMock.Fetch.thenFail(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.requestUplift(applicationId, requestedName, upliftRequestedBy))
      }
    }
  }

  "verifyUplift" should {
    val upliftRequestedBy = "email@example.com"

    "update the state of the application and create app in the API gateway when application is in pendingRequesterVerification state" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      AuditServiceMock.AuditWithTags.thenReturnSuccess()
      ApplicationRepoMock.Save.thenReturn(mock[ApplicationData])

      val expectedStateHistory = StateHistory(applicationId, State.PRODUCTION, Actor(upliftRequestedBy, COLLABORATOR), Some(PENDING_REQUESTER_VERIFICATION))
      val upliftRequest = StateHistory(applicationId, PENDING_GATEKEEPER_APPROVAL, Actor(upliftRequestedBy, COLLABORATOR), Some(TESTING))

      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))

      val expectedApplication: ApplicationData = application.copy(state = productionState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)
      StateHistoryRepoMock.FetchLatestByStateForApplication.thenReturnWhen(applicationId, PENDING_GATEKEEPER_APPROVAL)(upliftRequest)
      StateHistoryRepoMock.Insert.thenAnswer()

      val result: ApplicationStateChange = await(underTest.verifyUplift(generatedVerificationCode))
      ApplicationRepoMock.Save.verifyCalledWith(expectedApplication)
      ApiGatewayStoreMock.CreateApplication.verifyCalled()

      result shouldBe UpliftVerified
      
      StateHistoryRepoMock.Insert.verifyCalledWith(expectedStateHistory)
    }

    "fail if the application save fails" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))
      val saveException = new RuntimeException("application failed to save")

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)
      ApplicationRepoMock.Save.thenFail(saveException)

      intercept[RuntimeException] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "rollback if saving the state history fails" in new Setup {
      ApiGatewayStoreMock.CreateApplication.thenReturnHasSucceeded()
      val application: ApplicationData = anApplicationData(applicationId, pendingRequesterVerificationState(upliftRequestedBy))
      ApplicationRepoMock.Save.thenReturn(mock[ApplicationData])
      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)
      StateHistoryRepoMock.Insert.thenFailsWith(new RuntimeException("Expected test failure"))

      intercept[RuntimeException] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }

      ApplicationRepoMock.Save.verifyCalledWith(application)
    }

    "not update the state but result in success of the application when application is already in production state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, productionState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)

      val result: ApplicationStateChange = await(underTest.verifyUplift(generatedVerificationCode))
      result shouldBe UpliftVerified
      ApplicationRepoMock.Save.verifyNeverCalled()
    }

    "fail when application is in testing state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, testingState())

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "fail when application is in pendingGatekeeperApproval state" in new Setup {
      val application: ApplicationData = anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnWhen(generatedVerificationCode)(application)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }

    "fail when application is not found by verification code" in new Setup {
      anApplicationData(applicationId, pendingGatekeeperApprovalState(upliftRequestedBy))

      ApplicationRepoMock.FetchVerifiableUpliftBy.thenReturnNoneWhen(generatedVerificationCode)

      intercept[InvalidUpliftVerificationCode] {
        await(underTest.verifyUplift(generatedVerificationCode))
      }
    }
  }
}
