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

import com.github.tomakehurst.wiremock.client.WireMock._
import it.uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}
import play.api.http.Status.OK

object EmailStub extends Stub {

  override val stub: MockHost = MockHost(22223)

  def willPostEmailNotification() = {
    stub.mock.register(post(urlEqualTo("/hmrc/email"))
      .willReturn(
        aResponse()
          .withStatus(OK)
      )
    )
  }
}
