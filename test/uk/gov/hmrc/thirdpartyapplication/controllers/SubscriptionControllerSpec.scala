/*
 * Copyright 2021 HM Revenue & Customs
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
import uk.gov.hmrc.thirdpartyapplication.models.ApiIdentifier
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful, apply => _}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.UserId

class SubscriptionControllerSpec extends ControllerSpec {

  import play.api.test.Helpers._
  import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._

  val mockSubscriptionRepository: SubscriptionRepository = mock[SubscriptionRepository]

  override def builder() : GuiceApplicationBuilder = 
      super.builder()
      .overrides(bind[SubscriptionRepository].to(mockSubscriptionRepository))

  trait Setup {
    implicit lazy val materializer: Materializer = app.materializer
    def callEndpointWith[A: Writeable](request: Request[A]): Future[Result] = route(app, request).get
  }

  "getSubscribers" should {

    "return the subscribers from the repository" in new Setup {
      private val apiIdentifier = ApiIdentifier("hello", "1.0")
      private val subscribers = Set(ApplicationId.random, ApplicationId.random)
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(successful(subscribers))

      val result = callEndpointWith(FakeRequest(GET, s"/apis/${apiIdentifier.context}/versions/${apiIdentifier.version}/subscribers"))

      status(result) shouldBe OK
      contentAsJson(result).as[SubscribersResponse] shouldBe SubscribersResponse(subscribers)
    }

    "return the subscribers from the repository for a multi-segment API" in new Setup {
      private val apiIdentifier = ApiIdentifier("hello/world", "1.0")
      private val subscribers = Set(ApplicationId.random, ApplicationId.random)
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(successful(subscribers))

      val result = callEndpointWith(FakeRequest(GET, s"/apis/${apiIdentifier.context}/versions/${apiIdentifier.version}/subscribers"))

      status(result) shouldBe OK
      contentAsJson(result).as[SubscribersResponse] shouldBe SubscribersResponse(subscribers)
    }

    "return 500 if something goes wrong" in new Setup {
      private val apiIdentifier = ApiIdentifier("hello", "1.0")
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(failed(new RuntimeException("something went wrong")))

      val result = callEndpointWith(FakeRequest(GET, s"/apis/${apiIdentifier.context}/versions/${apiIdentifier.version}/subscribers"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "code").as[String] shouldBe "UNKNOWN_ERROR"
      (contentAsJson(result) \ "message").as[String] shouldBe "An unexpected error occurred"
    }
  }

  "getSubscriptionsForDeveloper using email" should {
    val developerEmail = "john.doe@example.com"

    "return the subscriptions from the repository" in new Setup {
      val expectedSubscriptions = Set(ApiIdentifier("hello-world", "1.0"))
      when(mockSubscriptionRepository.getSubscriptionsForDeveloper(developerEmail)).thenReturn(successful(expectedSubscriptions))

      val result = callEndpointWith(FakeRequest(GET, s"/developer/$developerEmail/subscriptions"))

      status(result) shouldBe OK
      contentAsJson(result).as[Set[ApiIdentifier]] shouldBe expectedSubscriptions
    }

    "return 500 if something goes wrong" in new Setup {
      when(mockSubscriptionRepository.getSubscriptionsForDeveloper(developerEmail)).thenReturn(failed(new RuntimeException("something went wrong")))

      val result = callEndpointWith(FakeRequest(GET, s"/developer/$developerEmail/subscriptions"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "code").as[String] shouldBe "UNKNOWN_ERROR"
      (contentAsJson(result) \ "message").as[String] shouldBe "An unexpected error occurred"
    }
  }

  "getSubscriptionsForDeveloper using userid" should {
    val userId = UserId.random

    "return the subscriptions from the repository" in new Setup {
      val expectedSubscriptions = Set(ApiIdentifier("hello-world", "1.0"))
      when(mockSubscriptionRepository.getSubscriptionsForDeveloper(userId)).thenReturn(successful(expectedSubscriptions))

      val result = callEndpointWith(FakeRequest(GET, s"/developer/${userId.value}/subscriptions"))

      status(result) shouldBe OK
      contentAsJson(result).as[Set[ApiIdentifier]] shouldBe expectedSubscriptions
    }

    "return 500 if something goes wrong" in new Setup {
      when(mockSubscriptionRepository.getSubscriptionsForDeveloper(userId)).thenReturn(failed(new RuntimeException("something went wrong")))

      val result = callEndpointWith(FakeRequest(GET, s"/developer/${userId.value}/subscriptions"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "code").as[String] shouldBe "UNKNOWN_ERROR"
      (contentAsJson(result) \ "message").as[String] shouldBe "An unexpected error occurred"
    }
  }
}
