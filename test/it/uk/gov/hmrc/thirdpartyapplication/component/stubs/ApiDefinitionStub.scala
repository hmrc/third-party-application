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
import play.api.http.Status.OK
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.ApiDefinition

object ApiDefinitionStub extends Stub {

  override val stub = MockHost(22221)

  def willReturnApisForApplication(applicationId: UUID, apiDefinitions: Seq[ApiDefinition]) = {
    stub.mock.register(get(urlEqualTo(s"/api-definition?applicationId=$applicationId"))
      .willReturn(
        aResponse()
          .withStatus(OK)
          .withBody(Json.toJson(apiDefinitions).toString())
      )
    )
  }
}
