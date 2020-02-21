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

package it.uk.gov.hmrc.thirdpartyapplication.component.stubs

import java.util.UUID

import com.github.tomakehurst.wiremock.client.WireMock._
import it.uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}
import play.api.http.Status.{CREATED, OK}
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier

object AwsApiGatewayStub extends Stub {

  override val stub: MockHost = MockHost(22229)

  private def updateUsagePlanURL(rateLimitTier: RateLimitTier): String = s"/v1/usage-plans/$rateLimitTier/api-keys"
  private def deleteAPIKeyURL(applicationName: String): String = s"/v1/api-keys/$applicationName"

  def willCreateOrUpdateApplication(applicationName: String, serverToken: String, usagePlan: RateLimitTier) = {
    stub.mock.register(post(urlEqualTo(updateUsagePlanURL(usagePlan)))
      .willReturn(
        aResponse()
          .withStatus(OK)
          .withBody(s"""{ "RequestId" : "${UUID.randomUUID().toString}" }""")
      )
    )
  }

  def willDeleteApplication(applicationName: String) = {
    stub.mock.register(delete(urlPathMatching(deleteAPIKeyURL(applicationName)))
      .willReturn(
        aResponse()
          .withStatus(OK)
          .withBody(s"""{ "RequestId" : "${UUID.randomUUID().toString}" }""")
      )
    )
  }
}
