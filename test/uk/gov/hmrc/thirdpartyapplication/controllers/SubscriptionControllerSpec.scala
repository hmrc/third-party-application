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

package uk.gov.hmrc.thirdpartyapplication.controllers

import akka.stream.Materializer
import play.api.http.Writeable
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{Request, Result}
import play.api.test.FakeRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.util.NoMetricsGuiceOneAppPerSuite
import play.api.libs.json.Json
import akka.stream.testkit.NoMaterializer
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.SubscriptionRepositoryMockModule

class SubscriptionControllerSpec extends ControllerSpec with NoMetricsGuiceOneAppPerSuite with SubscriptionRepositoryMockModule {

  import play.api.test.Helpers._

  override def builder() : GuiceApplicationBuilder = 
      super.builder()
      .overrides(bind[SubscriptionRepository].to(SubscriptionRepoMock.aMock))

  trait Setup {
    implicit lazy val materializer: Materializer = NoMaterializer
    def callEndpointWith[A: Writeable](request: Request[A]): Future[Result] = route(app, request).get
  }

  "getSubscribers" should {
      val apiIdentifier = "hello/world".asIdentifier

      def asUrl(apiIdentifier: ApiIdentifier): String = s"/apis/${apiIdentifier.context.value}/versions/${apiIdentifier.version.value}/subscribers"

    "return the subscribers from the repository" in new Setup {
      implicit val readsSubscribersResponse = Json.reads[SubscribersResponse]

      private val subscribers = Set(ApplicationId.random, ApplicationId.random)
      SubscriptionRepoMock.GetSubscribers.thenReturn(apiIdentifier)(subscribers)

      val result = callEndpointWith(FakeRequest(GET, asUrl(apiIdentifier)))

      status(result) shouldBe OK

      contentAsJson(result).as[SubscribersResponse] shouldBe SubscribersResponse(subscribers)
    }

    "return the subscribers from the repository for a multi-segment API" in new Setup {
      implicit val readsSubscribersResponse = Json.reads[SubscribersResponse]

      private val subscribers = Set(ApplicationId.random, ApplicationId.random)
      SubscriptionRepoMock.GetSubscribers.thenReturn(apiIdentifier)(subscribers)

      val result = callEndpointWith(FakeRequest(GET, asUrl(apiIdentifier)))

      status(result) shouldBe OK
      contentAsJson(result).as[SubscribersResponse] shouldBe SubscribersResponse(subscribers)
    }

    "return 500 if something goes wrong" in new Setup {
      SubscriptionRepoMock.GetSubscribers.thenFailWith(new RuntimeException("something went wrong"))

      val result = callEndpointWith(FakeRequest(GET, asUrl(apiIdentifier)))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "code").as[String] shouldBe "UNKNOWN_ERROR"
      (contentAsJson(result) \ "message").as[String] shouldBe "An unexpected error occurred"
    }
  }

  "getSubscriptionsForDeveloper using userid" should {
    val userId = UserId.random

    "return the subscriptions from the repository" in new Setup {
      val expectedSubscriptions = Set("hello/world".asIdentifier)
      SubscriptionRepoMock.GetSubscriptionsForDeveloper.thenReturnWhen(userId)(expectedSubscriptions)

      val result = callEndpointWith(FakeRequest(GET, s"/developer/${userId.value}/subscriptions"))

      status(result) shouldBe OK
      contentAsJson(result).as[Set[ApiIdentifier]] shouldBe expectedSubscriptions
    }

    "return 500 if something goes wrong" in new Setup {
      SubscriptionRepoMock.GetSubscriptionsForDeveloper.thenFailWith(new RuntimeException("something went wrong"))

      val result = callEndpointWith(FakeRequest(GET, s"/developer/${userId.value}/subscriptions"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "code").as[String] shouldBe "UNKNOWN_ERROR"
      (contentAsJson(result) \ "message").as[String] shouldBe "An unexpected error occurred"
    }
  }
}
