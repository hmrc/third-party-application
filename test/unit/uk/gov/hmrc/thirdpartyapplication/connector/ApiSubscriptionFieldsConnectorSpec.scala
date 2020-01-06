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

package unit.uk.gov.hmrc.thirdpartyapplication.connector

import java.util.UUID

import common.uk.gov.hmrc.thirdpartyapplication.common.LogSuppressing
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import play.api.http.Status._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, Upstream5xxResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiSubscriptionFieldsConfig, ApiSubscriptionFieldsConnector}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApiSubscriptionFieldsConnectorSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar with ScalaFutures with LogSuppressing {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val baseUrl = s"http://example.com"

  trait Setup {
    val mockHttpClient = mock[HttpClient]
    val config = ApiSubscriptionFieldsConfig(baseUrl)

    val underTest = new ApiSubscriptionFieldsConnector(mockHttpClient, config)

    def apiSubscriptionFieldsWillReturn(result: Future[HttpResponse]) = {
      when(mockHttpClient.DELETE[HttpResponse](*,*)(*, *, *)).thenReturn(result)
    }

    def verifyApiSubscriptionFieldsCalled(clientId: String) = {
      val expectedUrl = s"${config.baseUrl}/field/application/$clientId"
      verify(mockHttpClient).DELETE[HttpResponse](eqTo(expectedUrl), *)(*, *, *)
    }
  }

  "ApiSubscriptionFieldsConnector" should {

    val clientId = UUID.randomUUID().toString

    "succeed when the remote call returns No Content" in new Setup {

      apiSubscriptionFieldsWillReturn(Future(HttpResponse(NO_CONTENT)))

      await(underTest.deleteSubscriptions(clientId))

      verifyApiSubscriptionFieldsCalled(clientId)
    }

    "succeed when the remote call returns Not Found" in new Setup {
      apiSubscriptionFieldsWillReturn(Future(HttpResponse(NOT_FOUND)))

      await(underTest.deleteSubscriptions(clientId))

      verifyApiSubscriptionFieldsCalled(clientId)
    }

    "fail when the remote call returns Internal Server Error" in new Setup {
      apiSubscriptionFieldsWillReturn(Future.failed(Upstream5xxResponse("", INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR)))

      intercept[Upstream5xxResponse] {
        await(underTest.deleteSubscriptions(clientId))
      }

      verifyApiSubscriptionFieldsCalled(clientId)
    }
  }
}
