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

package uk.gov.hmrc.thirdpartyapplication.connector

import java.util.UUID

import play.api.http.Status._
import uk.gov.hmrc.http.{HeaderCarrier, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ApiSubscriptionFieldsConnectorSpec extends ConnectorSpec {

    def apiApplicationEventsWillReturnCreated(request: ApplicationEvent) =
      stubFor(
        post(urlMatching("/application-events/.*"))
        .withJsonRequestBody(request)
        .willReturn(
          aResponse()
          .withStatus(CREATED)
        )
      )

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val baseUrl = s"http://example.com"

  trait Setup {
    val http: HttpClient = app.injector.instanceOf[HttpClient]

    val config: ApiSubscriptionFieldsConfig = ApiSubscriptionFieldsConfig(wireMockUrl)

    val underTest = new ApiSubscriptionFieldsConnector(http, config)


    def apiSubscriptionFieldsWillReturn(result: Option[Unit]) = {
      when(mockHttpClient.DELETE[Option[Unit]](*,*)(*, *, *)).thenReturn(Future.successful(result))
    }

    def apiSubscriptionFieldsWillFail(result: Throwable) = {
      when(mockHttpClient.DELETE[Option[Unit]](*,*)(*, *, *)).thenReturn(Future.failed(result))
    }

    def verifyApiSubscriptionFieldsCalled(clientId: String) = {
      val expectedUrl = s"${config.baseUrl}/field/application/$clientId"
      verify(mockHttpClient).DELETE[Option[Unit]](eqTo(expectedUrl), *)(*, *, *)
    }
  }

  "ApiSubscriptionFieldsConnector" should {

    val clientId = UUID.randomUUID().toString

    "succeed when the remote call returns successfully" in new Setup {

      apiSubscriptionFieldsWillReturn(Some(()))

      await(underTest.deleteSubscriptions(clientId))

      verifyApiSubscriptionFieldsCalled(clientId)
    }

    "succeed when the remote call returns not found" in new Setup {

      apiSubscriptionFieldsWillReturn(None)

      await(underTest.deleteSubscriptions(clientId))

      verifyApiSubscriptionFieldsCalled(clientId)
    }

    "fail when the remote call returns Internal Server Error" in new Setup {
      apiSubscriptionFieldsWillFail(UpstreamErrorResponse("", INTERNAL_SERVER_ERROR))

      intercept[UpstreamErrorResponse] {
        await(underTest.deleteSubscriptions(clientId))
      }

      verifyApiSubscriptionFieldsCalled(clientId)
    }
  }
}
