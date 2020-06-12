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

package uk.gov.hmrc.thirdpartyapplication.component.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}
import play.api.http.Status.OK
import play.api.libs.json.Json
import uk.gov.hmrc.auth.core.Enrolment

object AuthStub extends Stub {

  override val stub = MockHost(22225)

  val json = Json.obj(
    "authorise" -> Json.arr((Enrolment("user-role") or Enrolment("super-user-role") or Enrolment("admin-role")).toJson),
    "retrieve" -> Json.arr()
  )

  def willValidateLoggedInUserHasGatekeeperRole() =
    stub.mock.register(post(urlPathEqualTo("/auth/authorise"))
      .withRequestBody(equalTo(json.toString))
      .willReturn(aResponse().withBody("""{"authorise":[{"identifiers":[],"state":"Activated","enrolment":"super-user-role"}],"retrieve":[]}""")
        .withStatus(OK)))

}
