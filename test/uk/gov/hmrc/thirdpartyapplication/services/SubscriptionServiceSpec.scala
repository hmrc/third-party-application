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

package uk.gov.hmrc.thirdpartyapplication.services

import com.github.t3hnar.bcrypt._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil

import play.api.libs.ws.WSResponse
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.{BRONZE, GOLD, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.mocks.AuditServiceMockModule

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful

class SubscriptionServiceSpec extends AsyncHmrcSpec  with ApplicationStateUtil {

  private val loggedInUser = "loggedin@example.com"
  private val productionToken = Token(ClientId("aaa"), "bbb", List(aSecret("secret1"), aSecret("secret2")))

  trait Setup extends AuditServiceMockModule {

    lazy val locked = false
    val mockApiGatewayStore = mock[ApiGatewayStore](withSettings.lenient())
    val mockApplicationRepository = mock[ApplicationRepository](withSettings.lenient())
    val mockStateHistoryRepository = mock[StateHistoryRepository](withSettings.lenient())
    val mockEmailConnector = mock[EmailConnector](withSettings.lenient())
    val mockSubscriptionRepository = mock[SubscriptionRepository](withSettings.lenient())
    val mockApiPlatformEventsService = mock[ApiPlatformEventService](withSettings.lenient())
    val response = mock[WSResponse]

    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
      LOGGED_IN_USER_EMAIL_HEADER -> loggedInUser,
      LOGGED_IN_USER_NAME_HEADER -> "John Smith"
    )

    val underTest = new SubscriptionService(
      mockApplicationRepository, mockSubscriptionRepository, AuditServiceMock.aMock, mockApiPlatformEventsService, mockApiGatewayStore)

    when(mockApiGatewayStore.createApplication(*, *)(*)).thenReturn(successful(HasSucceeded))
    when(mockApplicationRepository.save(*)).thenAnswer((a: ApplicationData) => successful(a))
    when(mockSubscriptionRepository.add(*[ApplicationId], *)).thenReturn(successful(HasSucceeded))
    when(mockSubscriptionRepository.remove(*[ApplicationId], *)).thenReturn(successful(HasSucceeded))
    when(mockApiPlatformEventsService.sendApiSubscribedEvent(*, *[ApiContext], *[ApiVersion])(*)).thenReturn(successful(true))
    when(mockApiPlatformEventsService.sendApiUnsubscribedEvent(*, *[ApiContext], *[ApiVersion])(*)).thenReturn(successful(true))
  }

  private def aSecret(secret: String): ClientSecret = ClientSecret(secret.takeRight(4),  hashedSecret = secret.bcrypt(4))


  "isSubscribed" should {
    val applicationId = ApplicationId.random
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

  "fetchAllSubscriptionsForApplication" should {
    val applicationId = ApplicationId.random

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(None))

      intercept[NotFoundException] {
        await(underTest.fetchAllSubscriptionsForApplication(applicationId))
      }

      verifyZeroInteractions(mockSubscriptionRepository)
    }

    "fetch all API subscriptions from api-definition for the given application id when an application exists" in new Setup {
      val applicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockSubscriptionRepository.getSubscriptions(applicationId)).thenReturn(successful(List("context".asIdentifier)))

      val result = await(underTest.fetchAllSubscriptionsForApplication(applicationId))

      result shouldBe Set("context".asIdentifier)
    }
  }

  "createSubscriptionForApplicationMinusChecks" should {
    val applicationId = ApplicationId.random
    val applicationData = anApplicationData(applicationId, rateLimitTier = Some(GOLD))
    val api = ApiIdentifier.random

    "create a subscription in Mongo for the given application when an application exists in the repository" in new Setup {

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      when(mockSubscriptionRepository.getSubscriptions(applicationId)).thenReturn(successful(List.empty))
      AuditServiceMock.Audit.thenReturnSuccess()

      val result: HasSucceeded = await(underTest.createSubscriptionForApplicationMinusChecks(applicationId, api))

      result shouldBe HasSucceeded

      verify(mockSubscriptionRepository).add(applicationId, api)
      verify(mockApiPlatformEventsService).sendApiSubscribedEvent(any[ApplicationData], eqTo(api.context), eqTo(api.version))(any[HeaderCarrier])

      val capturedParameters = AuditServiceMock.Audit.verifyData(Subscribed)
      capturedParameters.get("applicationId") should be (Some(applicationId.value.toString))
      capturedParameters.get("apiVersion") should be (Some(api.version.value))
      capturedParameters.get("apiContext") should be (Some(api.context.value))
    }
  }

  "removeSubscriptionForApplication" should {
    val applicationId = ApplicationId.random
    val api = ApiIdentifier.random

    "throw a NotFoundException when no application exists in the repository for the given application id" in new Setup {
      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(None))

      intercept[NotFoundException] {
        await(underTest.removeSubscriptionForApplication(applicationId, api))
      }
    }

    "remove the API subscription from Mongo for the given application id when an application exists" in new Setup {
      val applicationData = anApplicationData(applicationId)

      when(mockApplicationRepository.fetch(applicationId)).thenReturn(successful(Some(applicationData)))
      AuditServiceMock.Audit.thenReturnSuccess()

      val result = await(underTest.removeSubscriptionForApplication(applicationId, api))

      result shouldBe HasSucceeded
      verify(mockSubscriptionRepository).remove(applicationId, api)
      verify(mockApiPlatformEventsService).sendApiUnsubscribedEvent(any[ApplicationData], eqTo(api.context), eqTo(api.version))(any[HeaderCarrier])

      val capturedParameters = AuditServiceMock.Audit.verifyData(Unsubscribed)
      capturedParameters.get("applicationId") should be (Some(applicationId.value.toString))
      capturedParameters.get("apiVersion") should be (Some(api.version.value))
      capturedParameters.get("apiContext") should be (Some(api.context.value))
    }
  }

  "searchCollaborators" should {
    "return emails" in new Setup {
      val context = ApiContext.random
      val version = ApiVersion.random
      val emails = List("user@example.com", "dev@example.com")
      val partialEmailMatch = "partialEmail"

      when(mockSubscriptionRepository.searchCollaborators(eqTo(context), eqTo(version), eqTo(Some(partialEmailMatch)))).thenReturn(successful(emails))

      val result = await(underTest.searchCollaborators(context, version, Some(partialEmailMatch)))
      result shouldBe emails
    }
  }

  private val requestedByEmail = "john.smith@example.com"

  private def anApplicationData(applicationId: ApplicationId, state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, ADMINISTRATOR, UserId.random)),
                                rateLimitTier: Option[RateLimitTier] = Some(BRONZE)) = {
    new ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      state,
      Standard(),
      LocalDateTime.now(clock),
      Some(LocalDateTime.now(clock)),
      rateLimitTier = rateLimitTier
    )
  }
}