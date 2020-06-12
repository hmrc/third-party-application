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

package uk.gov.hmrc.thirdpartyapplication.connector

import java.util.UUID

import play.api.http.Status.INTERNAL_SERVER_ERROR
import uk.gov.hmrc.http.{HeaderCarrier, Upstream5xxResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.{ApiDefinition, ApiStatus, ApiVersion}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApiDefinitionConnectorSpec extends ConnectorSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val baseUrl = s"https://example.com"

  val apiDefinitionWithStableStatus = ApiDefinition("api-service", "api-name", "api-context",
    List(ApiVersion("1.0", ApiStatus.STABLE, None)), Some(false))

  val apiDefinitionWithBetaStatus = ApiDefinition("api-service", "api-name", "api-context",
    List(ApiVersion("1.0", ApiStatus.BETA, None)), Some(false))

  trait Setup {
    val config = ApiDefinitionConfig(baseUrl)
    val mockHttpClient = mock[HttpClient]

    val underTest = new ApiDefinitionConnector(mockHttpClient, config)

    def apiDefinitionWillReturn(result: Future[Seq[ApiDefinition]]) = {
      when(mockHttpClient.GET[Seq[ApiDefinition]](*)(*, *, *)).thenReturn(result)
    }

    def verifyApiDefinitionCalled(applicationId: UUID) = {
      val expectedUrl = s"${config.baseUrl}/api-definition?applicationId=$applicationId"
      verify(mockHttpClient).GET[Seq[ApiDefinition]](eqTo(expectedUrl))(*, *, *)
    }
  }

  "fetchAPIs" should {

    val applicationId: UUID = UUID.randomUUID()

    "return the APIs available for an application" in new Setup {

      apiDefinitionWillReturn(Future.successful(Seq(apiDefinitionWithStableStatus, apiDefinitionWithBetaStatus)))

      val result = await(underTest.fetchAllAPIs(applicationId))

      result shouldBe Seq(apiDefinitionWithStableStatus, apiDefinitionWithBetaStatus)
      verifyApiDefinitionCalled(applicationId)
    }

    "fail when api-definition returns a 500" in new Setup {

      apiDefinitionWillReturn(Future.failed(Upstream5xxResponse("", INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR)))

      intercept[RuntimeException] {
        await(underTest.fetchAllAPIs(applicationId))
      }

      verifyApiDefinitionCalled(applicationId)
    }

  }
}
