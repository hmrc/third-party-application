/*
 * Copyright 2019 HM Revenue & Customs
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
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status.{INTERNAL_SERVER_ERROR, OK}
import play.api.mvc.Result
import play.api.test.FakeRequest
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.controllers._
import uk.gov.hmrc.thirdpartyapplication.models.APIIdentifier
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.{failed, successful, apply => _}

class SubscriptionControllerSpec extends UnitSpec with ScalaFutures with MockitoSugar with WithFakeApplication {

  implicit lazy val materializer: Materializer = fakeApplication.materializer

  trait Setup {
    val mockSubscriptionRepository: SubscriptionRepository = mock[SubscriptionRepository]

    val underTest = new SubscriptionController(mockSubscriptionRepository)
  }

  "getSubscribers" should {

    "return the subscribers from the repository" in new Setup {
      private val apiIdentifier = APIIdentifier("hello", "1.0")
      private val subscribers = Set(UUID.randomUUID(), UUID.randomUUID())
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(successful(subscribers))

      val result: Result = await(underTest.getSubscribers(apiIdentifier.context, apiIdentifier.version)(FakeRequest()))

      status(result) shouldBe OK
      jsonBodyOf(result).as[SubscribersResponse] shouldBe SubscribersResponse(subscribers)
    }

    "return 500 if something goes wrong" in new Setup {
      private val apiIdentifier = APIIdentifier("hello", "1.0")
      when(mockSubscriptionRepository.getSubscribers(apiIdentifier)).thenReturn(failed(new RuntimeException("something went wrong")))

      val result: Result = await(underTest.getSubscribers(apiIdentifier.context, apiIdentifier.version)(FakeRequest()))

      status(result) shouldBe INTERNAL_SERVER_ERROR
      (jsonBodyOf(result) \ "code").as[String] shouldBe "UNKNOWN_ERROR"
      (jsonBodyOf(result) \ "message").as[String] shouldBe "An unexpected error occurred"
    }
  }
}
