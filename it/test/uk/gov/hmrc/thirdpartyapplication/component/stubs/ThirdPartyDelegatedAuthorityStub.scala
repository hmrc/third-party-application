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

package uk.gov.hmrc.thirdpartyapplication.component.stubs

import com.github.tomakehurst.wiremock.client.WireMock._

import play.api.http.Status.NO_CONTENT

import uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}

object ThirdPartyDelegatedAuthorityStub extends Stub {
  override val stub = MockHost(19606)

  def willRevokeApplicationAuthorities() = {
    stub.mock.register(delete(urlPathMatching("/authority/*"))
      .willReturn(aResponse().withStatus(NO_CONTENT)))
  }
}
