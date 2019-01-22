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

import java.net.URLEncoder

import com.github.tomakehurst.wiremock.client.WireMock._
import it.uk.gov.hmrc.thirdpartyapplication.component.{Stub, MockHost}
import play.api.http.Status.OK
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.models.UserResponse

object ThirdPartyDeveloperStub extends Stub {

  override val stub = MockHost(22224)

  def willReturnTheDeveloper(user: UserResponse) = {
    val email = URLEncoder.encode(user.email, "UTF-8")
    stub.mock.register(get(urlEqualTo(s"/developer?email=$email"))
      .willReturn(
        aResponse()
          .withStatus(OK)
          .withBody(Json.toJson(user).toString())
      )
    )
  }
}
