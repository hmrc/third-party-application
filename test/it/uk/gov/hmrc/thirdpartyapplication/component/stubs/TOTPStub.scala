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

package it.uk.gov.hmrc.thirdpartyapplication.component.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.stubbing.Scenario
import it.uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}
import play.api.http.Status.CREATED
import play.api.libs.json.Json

object TOTPStub extends Stub {

  override val stub = MockHost(22226)

  private val productionState = "productionState"
  private val sandboxState = "sandboxState"

  private val totpUrl = "/time-based-one-time-password/secret"

  private val productionTotpJson = Json.obj("secret" -> "prod-secret", "id" -> "prod-id").toString()
  private val sandboxTotpJson = Json.obj("secret" -> "sandbox-secret", "id" -> "sandbox-id").toString()

  def willReturnTOTP(scenarioName: String) = {

    stub.server.stubFor(
      post(urlEqualTo(totpUrl))
        .inScenario(scenarioName)
        .whenScenarioStateIs(Scenario.STARTED)
        .willSetStateTo(sandboxState)
        .willReturn(
          aResponse()
            .withStatus(CREATED)
            .withBody(productionTotpJson)
        )
    )

    stub.server.stubFor(
      post(urlEqualTo(totpUrl))
        .inScenario(scenarioName)
        .whenScenarioStateIs(productionState)
        .willSetStateTo(sandboxState)
        .willReturn(
          aResponse()
            .withStatus(CREATED)
            .withBody(productionTotpJson)
        )
    )

    stub.server.stubFor(
      post(urlEqualTo(totpUrl))
        .inScenario(scenarioName)
        .whenScenarioStateIs(sandboxState)
        .willSetStateTo(productionState)
        .willReturn(
          aResponse()
            .withStatus(CREATED)
            .withBody(sandboxTotpJson)
        )
    )

  }

}
