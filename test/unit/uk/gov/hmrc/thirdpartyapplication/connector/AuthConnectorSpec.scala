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

package unit.uk.gov.hmrc.thirdpartyapplication.connector

import org.mockito.Matchers.{any, eq => meq}
import org.mockito.Mockito.{verify, when}
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, Upstream4xxResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AuthConnectorSpec extends UnitSpec with MockitoSugar with Matchers with ScalaFutures {

  implicit val hc = HeaderCarrier()
  val baseUrl = s"http://example.com"

  trait Setup {
    val mockHttpClient = mock[HttpClient]
    val config = AuthConfig(baseUrl)
    val connector = new AuthConnector(mockHttpClient, config)

    def authWillReturn(result: Future[HttpResponse]) = {
      when(mockHttpClient.GET[HttpResponse](any())(any(), any(), any())).thenReturn(result)
    }

    def verifyAuthCalled(scope: String, role: Option[String] = None) = {
      val authUrl = s"${config.baseUrl}/auth/authenticate/user/authorise"
      val urlWithScope = s"$authUrl?scope=$scope"
      val expectedUrl = role.fold(urlWithScope)(r => s"$urlWithScope&role=$r")
      verify(mockHttpClient).GET[HttpResponse](meq(expectedUrl))(any(), any(), any())
    }
  }

  "authorised" should {

    val scope = "api"

    "return true if only scope is sent and the response is 200 OK" in new Setup {

      authWillReturn(Future(HttpResponse(OK)))

      val result = await(connector.authorized("api", None))

      verifyAuthCalled(scope)
      result shouldBe true
    }

    "return true if scope and role are sent and the response is 200 OK" in new Setup {

      authWillReturn(Future(HttpResponse(OK)))

      val role = "gatekeeper"
      val result = await(connector.authorized("api", Some(role)))

      verifyAuthCalled(scope, Some(role))
      result shouldBe true
    }

    "return false if scope and role are sent but the response is 401 UNAUTHORIZED" in new Setup {
      authWillReturn(Future.failed(Upstream4xxResponse("", UNAUTHORIZED, UNAUTHORIZED)))

      val role = "gatekeeper"
      val result = await(connector.authorized("api", Some(role)))

      verifyAuthCalled(scope, Some(role))
      result shouldBe false
    }
  }
}
