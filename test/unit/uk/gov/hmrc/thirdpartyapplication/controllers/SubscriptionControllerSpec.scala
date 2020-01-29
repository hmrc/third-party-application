/*
 * Copyright 2020 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.controllers

import java.util.UUID

import akka.stream.Materializer
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Writeable
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{Request, Result}
import play.api.test.FakeRequest
import uk.gov.hmrc.thirdpartyapplication.controllers._
import uk.gov.hmrc.thirdpartyapplication.models.APIIdentifier
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful, apply => _}

class SubscriptionControllerSpec extends AsyncHmrcSpec with GuiceOneAppPerSuite {

  import play.api.test.Helpers._
  import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._

  val mockSubscriptionRepository: SubscriptionRepository = mock[SubscriptionRepository]

  override implicit lazy val app: Application = GuiceApplicationBuilder()
    .overrides(bind[SubscriptionRepository].to(mockSubscriptionRepository))
    .build

  trait Setup {
    implicit lazy val materializer: Materializer = app.materializer
    def callEndpointWith[A: Writeable](request: Request[A]): Future[Result] = route(app, request).get
  }

  "getSubscribers" should {

    "return the subscribers from the repository" in new Setup {
      private val apiIdentifier = APIIdentifier("hello", "1.0")
      private val subscribers = Set(UUID.randomUUID(), UUID.randomUUID())
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(successful(subscribers))

      val result = callEndpointWith(FakeRequest(GET, s"/apis/${apiIdentifier.context}/versions/${apiIdentifier.version}/subscribers"))

      status(result) shouldBe OK
      contentAsJson(result).as[SubscribersResponse] shouldBe SubscribersResponse(subscribers)
    }

    "return the subscribers from the repository for a multi-segment API" in new Setup {
      private val apiIdentifier = APIIdentifier("hello/world", "1.0")
      private val subscribers = Set(UUID.randomUUID(), UUID.randomUUID())
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(successful(subscribers))

      val result = callEndpointWith(FakeRequest(GET, s"/apis/${apiIdentifier.context}/versions/${apiIdentifier.version}/subscribers"))

      status(result) shouldBe OK
      contentAsJson(result).as[SubscribersResponse] shouldBe SubscribersResponse(subscribers)
    }

    "return 500 if something goes wrong" in new Setup {
      private val apiIdentifier = APIIdentifier("hello", "1.0")
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(failed(new RuntimeException("something went wrong")))

      val result = callEndpointWith(FakeRequest(GET, s"/apis/${apiIdentifier.context}/versions/${apiIdentifier.version}/subscribers"))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "code").as[String] shouldBe "UNKNOWN_ERROR"
      (contentAsJson(result) \ "message").as[String] shouldBe "An unexpected error occurred"
    }
  }
}
