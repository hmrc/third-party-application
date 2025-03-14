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

package uk.gov.hmrc.thirdpartyapplication.services

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful

import org.mockito.Strictness

import play.api.libs.ws.WSResponse
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, _}
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SubscribeToApi
import uk.gov.hmrc.thirdpartyapplication.mocks.{ApplicationCommandDispatcherMockModule, AuditServiceMockModule}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class SubscriptionServiceSpec extends AsyncHmrcSpec with CollaboratorTestData with CommonApplicationId with StoredApplicationFixtures {

  trait SetupWithoutHc extends AuditServiceMockModule with ApplicationCommandDispatcherMockModule {

    lazy val locked                    = false
    val mockApplicationRepository      = mock[ApplicationRepository](withSettings.strictness(Strictness.Lenient))
    val mockSubscriptionRepository     = mock[SubscriptionRepository](withSettings.strictness(Strictness.Lenient))
    val mockApplicationCommandDispatch = mock[ApplicationCommandDispatcher](withSettings.strictness(Strictness.Lenient))
    val response                       = mock[WSResponse]

    val underTest = new SubscriptionService(
      mockApplicationRepository,
      mockSubscriptionRepository,
      ApplicationCommandDispatcherMock.aMock,
      clock
    )
    when(mockApplicationRepository.save(*)).thenAnswer((a: StoredApplication) => successful(a))
    when(mockSubscriptionRepository.add(*[ApplicationId], *)).thenReturn(successful(HasSucceeded))
    when(mockSubscriptionRepository.remove(*[ApplicationId], *)).thenReturn(successful(HasSucceeded))
  }

  "isSubscribed" should {

    val api = ApiIdentifier.random

    "return true when the application is subscribed to a given API version" in new Setup {
      when(mockSubscriptionRepository.isSubscribed(applicationId, api)).thenReturn(successful(true))

      val result = await(underTest.isSubscribed(applicationId, api))

      result shouldBe true
    }

    "return false when the application is not subscribed to a given API version" in new Setup {
      when(mockSubscriptionRepository.isSubscribed(applicationId, api)).thenReturn(successful(false))

      val result = await(underTest.isSubscribed(applicationId, api))

      result shouldBe false
    }
  }

  trait Setup extends SetupWithoutHc {

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> adminOne.emailAddress.text,
      LOGGED_IN_USER_NAME_HEADER  -> "John Smith"
    )
  }

  trait SetupWithGKUser extends SetupWithoutHc {

    val gkUserEmail = "john.smith@hmrc.co.uk"

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> gkUserEmail,
      LOGGED_IN_USER_NAME_HEADER  -> "John Smith"
    )
  }

  "fetchAllSubscriptionsForApplication" should {

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(None))

      intercept[NotFoundException] {
        await(underTest.fetchAllSubscriptionsForApplication(applicationId))
      }

      verifyZeroInteractions(mockSubscriptionRepository)
    }

    "fetch all API subscriptions from api-definition for the given application id when an application exists" in new Setup {
      val applicationData = storedApp

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockSubscriptionRepository.getSubscriptions(applicationId)).thenReturn(successful(Set("context".asIdentifier)))

      val result = await(underTest.fetchAllSubscriptionsForApplication(applicationId))

      result shouldBe Set("context".asIdentifier)
    }
  }

  "updateApplicationForApiSubscription" should {

    val apiIdentifier = ApiIdentifier.random

    "return successfully using the correct Actors.AppCollaborator if the collaborator is a member of the application" in new Setup {
      val application = storedApp
      val actor       = Actors.AppCollaborator(adminOne.emailAddress)

      ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccess(application)

      val result = await(underTest.updateApplicationForApiSubscription(applicationId, application.name, application.collaborators, apiIdentifier))

      result shouldBe HasSucceeded
      ApplicationCommandDispatcherMock.Dispatch.verifyCalledWith(applicationId).asInstanceOf[SubscribeToApi].actor shouldBe actor
    }

    "return successfully using a GatekeeperUserCollaborator if there are no developers in the header carrier" in new SetupWithoutHc {
      implicit val hc: HeaderCarrier = HeaderCarrier()
      val applicationData            = storedApp
      val actor                      = Actors.GatekeeperUser("Gatekeeper Admin")

      ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccess(applicationData)

      val result = await(underTest.updateApplicationForApiSubscription(applicationId, applicationData.name, applicationData.collaborators, apiIdentifier))

      result shouldBe HasSucceeded
      ApplicationCommandDispatcherMock.Dispatch.verifyCalledWith(applicationId).asInstanceOf[SubscribeToApi].actor shouldBe actor
    }

    "return successfully using a GatekeeperUserCollaborator if the logged in user is not a member of the application" in new SetupWithGKUser {
      val applicationData = storedApp
      val actor           = Actors.GatekeeperUser(gkUserEmail)

      ApplicationCommandDispatcherMock.Dispatch.thenReturnSuccess(applicationData)

      val result = await(underTest.updateApplicationForApiSubscription(applicationId, applicationData.name, applicationData.collaborators, apiIdentifier))

      result shouldBe HasSucceeded
      ApplicationCommandDispatcherMock.Dispatch.verifyCalledWith(applicationId).asInstanceOf[SubscribeToApi].actor shouldBe actor
    }

    "throw an exception if the application has not updated" in new Setup {
      val applicationData = storedApp.copy(collaborators = Set.empty)
      val errorMessage    = "Not valid"

      ApplicationCommandDispatcherMock.Dispatch.thenReturnFailed(errorMessage)

      intercept[FailedToSubscribeException] {
        await(underTest.updateApplicationForApiSubscription(applicationId, applicationData.name, applicationData.collaborators, apiIdentifier))
      }
    }
  }

  "searchCollaborators" should {
    "return emails" in new Setup {
      val context           = ApiContext.random
      val version           = ApiVersionNbr.random
      val emails            = List("user@example.com", "dev@example.com")
      val partialEmailMatch = "partialEmail"

      when(mockSubscriptionRepository.searchCollaborators(eqTo(context), eqTo(version), eqTo(Some(partialEmailMatch)))).thenReturn(successful(emails))

      val result = await(underTest.searchCollaborators(context, version, Some(partialEmailMatch)))
      result shouldBe emails
    }
  }

  // private val requestedByEmail = "john.smith@example.com"
}
