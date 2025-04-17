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

package uk.gov.hmrc.apiplatform.modules.subscriptionfields.connectors

import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._

import play.api.http.Status._
import uk.gov.hmrc.http.test.HttpClientV2Support
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}

import uk.gov.hmrc.apiplatform.modules.common.connectors.ConnectorSpec
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector

class ApiSubscriptionFieldsConnectorSpec extends ConnectorSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val clientId = ClientId.random

  trait Setup extends HttpClientV2Support {
    val config: ApiSubscriptionFieldsConnector.Config = uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector.Config(wireMockUrl)

    val underTest = new ApiSubscriptionFieldsConnector(httpClientV2, config)

    def apiSubscriptionFieldsWillReturn(status: Int) =
      stubFor(
        delete(urlEqualTo(s"/field/application/${clientId.value}"))
          .willReturn(
            aResponse()
              .withStatus(status)
          )
      )
  }

  "ApiSubscriptionFieldsConnector" should {
    "succeed when the remote call returns successfully" in new Setup {
      apiSubscriptionFieldsWillReturn(OK)

      await(underTest.delete(clientId))
    }

    "succeed when the remote call returns not found" in new Setup {
      apiSubscriptionFieldsWillReturn(NOT_FOUND)

      await(underTest.delete(clientId))
    }

    "fail when the remote call returns Internal Server Error" in new Setup {
      apiSubscriptionFieldsWillReturn(INTERNAL_SERVER_ERROR)

      intercept[UpstreamErrorResponse] {
        await(underTest.delete(clientId))
      }
    }
  }
}
