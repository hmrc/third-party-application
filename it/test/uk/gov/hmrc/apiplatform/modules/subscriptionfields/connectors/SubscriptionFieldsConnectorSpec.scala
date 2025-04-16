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

import java.net.URLEncoder
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._

import play.api.http.Status._
import play.api.libs.json.Json
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.connectors.WiremockSugar
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector.SubscriptionFieldsPutRequest
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models.{ApiFieldMapFixtures, FieldDefinitionFixtures, FieldNameFixtures, FieldValueFixtures, FieldsFixtures}

class SubscriptionFieldsConnectorSpec
    extends ServerBaseISpec
    with WiremockSugar
    with ApiIdentifierFixtures
    with FieldsFixtures
    with FieldNameFixtures
    with FieldValueFixtures
    with FieldDefinitionFixtures
    with ApiFieldMapFixtures {

  trait SetupPrincipal {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val clientId                   = ClientId("123")

    val httpClient = app.injector.instanceOf[HttpClientV2]
    val config     = uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector.Config(wireMockUrl)
    val connector  = new ApiSubscriptionFieldsConnector(httpClient, config)
    val fieldsId   = UUID.randomUUID()

    val bulkSubsOne = Json.parse(
      s"""{"subscriptions":[{"clientId":"123","apiContext":"$apiContextOne","apiVersion":"$apiVersionNbrOne", "fieldsId":"$fieldsId","fields":{"$fieldNameOne":"$fieldValueOne"}}]}"""
    )

    val fieldDefJson =
      s"""{
            "name": "${fieldDefnOne.name}",
            "description": "${fieldDefnOne.description}",
            "hint": "${fieldDefnOne.hint}",
            "type": "${fieldDefnOne.`type`}",
            "shortDescription": "${fieldDefnOne.shortDescription}"
        }"""

    val bulkSubsDefnOne = Json.parse(
      s"""{"apis":[{"apiContext":"$apiContextOne","apiVersion":"$apiVersionNbrOne", "fieldDefinitions":[$fieldDefJson]}]}"""
    )
  }

  def encode(in: ApiContext): String = URLEncoder.encode(in.value, "UTF-8")

  "SubscriptionFieldsConnector" should {
    "retrieve all field definitions" in new SetupPrincipal {
      stubFor(
        get(urlEqualTo(s"/definition"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(bulkSubsDefnOne)
          )
      )

      val expected = Map(
        apiContextOne -> Map(
          apiVersionNbrOne -> Map(
            fieldDefnOne.name -> fieldDefnOne
          )
        )
      )

      await(connector.fetchAllFieldDefinitions()) shouldBe expected
    }

    "retrieve all field values by client id" in new SetupPrincipal {
      stubFor(
        get(urlEqualTo(s"/field/application/${clientId}"))
          .willReturn(
            aResponse()
              .withStatus(OK)
              .withJsonBody(bulkSubsOne)
          )
      )

      val expected = Map(
        apiContextOne -> Map(
          apiVersionNbrOne -> fieldsMapOne
        )
      )

      await(connector.fetchFieldValues(clientId)) shouldBe expected
    }

    "save field values" should {
      "work with good values" in new SetupPrincipal {
        val request: SubscriptionFieldsPutRequest = SubscriptionFieldsPutRequest(clientId, apiContextOne, apiVersionNbrOne, fieldsMapOne)

        stubFor(
          put(urlEqualTo(s"/field/application/${clientId}/context/${encode(apiContextOne)}/version/${apiVersionNbrOne}"))
            .withJsonRequestBody(request)
            .willReturn(
              aResponse()
                .withStatus(OK)
            )
        )

        val result = await(connector.saveFieldValues(clientId, apiIdentifierOne, fieldsMapOne))

        result shouldBe Right(())
      }

      "return field errors with bad values" in new SetupPrincipal {
        val request: SubscriptionFieldsPutRequest = SubscriptionFieldsPutRequest(clientId, apiContextOne, apiVersionNbrOne, fieldsMapOne)
        val error                                 = "This is wrong"

        stubFor(
          put(urlEqualTo(s"/field/application/${clientId}/context/${encode(apiContextOne)}/version/${apiVersionNbrOne}"))
            .withJsonRequestBody(request)
            .willReturn(
              aResponse()
                .withStatus(BAD_REQUEST)
                .withJsonBody(Map(fieldNameOne -> error))
            )
        )

        val result = await(connector.saveFieldValues(clientId, apiIdentifierOne, fieldsMapOne))

        result shouldBe Left(Map(fieldNameOne -> error))
      }
    }
  }

  "return simple url" in new SetupPrincipal {
    connector.urlSubscriptionFieldValues(
      ClientId("1"),
      ApiIdentifier(ApiContext("path"), ApiVersionNbr("1"))
    ).toString shouldBe "http://localhost:22222/field/application/1/context/path/version/1"
  }
  "return complex encoded url" in new SetupPrincipal {
    connector.urlSubscriptionFieldValues(
      ClientId("1 2"),
      ApiIdentifier(ApiContext("path1/path2"), ApiVersionNbr("1.0 demo"))
    ).toString shouldBe "http://localhost:22222/field/application/1%202/context/path1%2Fpath2/version/1.0%20demo"
  }

}
