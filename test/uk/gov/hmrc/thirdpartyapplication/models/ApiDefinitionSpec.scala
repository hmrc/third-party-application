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

package uk.gov.hmrc.thirdpartyapplication.models

import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec

class ApiDefinitionSpec extends HmrcSpec {

  private val apiDefinitionWithStableStatus = ApiDefinition("api-service", "api-name", "api-context",
    List(ApiVersion("1.0", ApiStatus.STABLE, None)))

  private val apiDefinitionWithBetaStatus = ApiDefinition("api-service", "api-name", "api-context",
    List(ApiVersion("1.0", ApiStatus.BETA, None)))

  private val apiDefinitionWithIsTestSupportFlag = ApiDefinition("api-service", "api-name", "api-context",
    List(ApiVersion("1.0", ApiStatus.STABLE, None)), Some(true))

  private val apiDefinitionWithStableStatusJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "STABLE"
      |    }
      |  ]
      |}""".stripMargin

  private val apiDefinitionWithPublishedStatusJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PUBLISHED"
      |    }
      |  ]
      |}""".stripMargin

  private val apiDefinitionWithPrototypedStatusJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PROTOTYPED"
      |    }
      |  ]
      |}""".stripMargin

  private val apiDefinitionWithIsTestSupportFlagJson =
    """{
      |  "serviceName": "api-service",
      |  "name": "api-name",
      |  "context": "api-context",
      |  "versions": [
      |    {
      |      "version": "1.0",
      |      "status": "PUBLISHED"
      |    }
      |  ],
      |  "isTestSupport": true
      |}""".stripMargin

  "APIDefinition" should {

    "map a status of STABLE in the JSON to STABLE in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithStableStatusJson))

      result.asOpt shouldBe Some(apiDefinitionWithStableStatus)
    }

    "map a status of PROTOTYPED in the JSON to BETA in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithPrototypedStatusJson))

      result.asOpt shouldBe Some(apiDefinitionWithBetaStatus)
    }

    "map a status of PUBLISHED in the JSON to STABLE in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithPublishedStatusJson))

      result.asOpt shouldBe Some(apiDefinitionWithStableStatus)
    }

    "map a isTestSupport flag when true in the JSON to true in the model" in {

      val result = Json.fromJson[ApiDefinition](Json.parse(apiDefinitionWithIsTestSupportFlagJson))

      result.asOpt shouldBe Some(apiDefinitionWithIsTestSupportFlag)
    }

  }
}
