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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import common.uk.gov.hmrc.thirdpartyapplication.common.LogSuppressing
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status._
import uk.gov.hmrc.thirdpartyapplication.connector.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse}
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.X_REQUEST_ID_HEADER

class ApiSubscriptionFieldsConnectorSpec  extends UnitSpec with WithFakeApplication with MockitoSugar with ScalaFutures
  with BeforeAndAfterEach with LogSuppressing {

  implicit val hc = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
  val stubPort = sys.env.getOrElse("WIREMOCK", "21212").toInt
  val stubHost = "localhost"
  val wireMockUrl = s"http://$stubHost:$stubPort"
  val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  trait Setup {
    val clientId = "client-id"
    val underTest = new ApiSubscriptionFieldsConnector {
      override lazy val serviceUrl = wireMockUrl
    }
  }

  override def beforeEach() {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
  }

  override def afterEach() {
    wireMockServer.resetMappings()
    wireMockServer.stop()
  }

  "ApiSubscriptionFieldsConnector" should {
    "succeed when the remote call returns No Content" in new Setup {
      val url = s"/field/application/$clientId"
      stubFor(delete(urlEqualTo(url)).willReturn(aResponse().withStatus(NO_CONTENT)))

      await(underTest.deleteSubscriptions(clientId))

      verify(1, deleteRequestedFor(urlEqualTo(url)))
    }

    "succeed when the remote call returns Not Found" in new Setup {
      val url = s"/field/application/$clientId"
      stubFor(delete(urlEqualTo(url)).willReturn(aResponse().withStatus(NOT_FOUND)))

      await(underTest.deleteSubscriptions(clientId))

      verify(1, deleteRequestedFor(urlEqualTo(url)))
    }

    "fail when the remote call returns Internal Server Error" in new Setup {
      val url = s"/field/application/$clientId"
      stubFor(delete(urlEqualTo(url)).willReturn(aResponse().withStatus(INTERNAL_SERVER_ERROR)))

      intercept[Upstream5xxResponse] {
        await(underTest.deleteSubscriptions(clientId))
      }

      verify(1, deleteRequestedFor(urlEqualTo(url)))
    }
  }
}
