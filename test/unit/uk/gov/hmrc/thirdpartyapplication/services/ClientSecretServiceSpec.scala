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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec

class ClientSecretServiceSpec extends HmrcSpec {

  val underTest = new ClientSecretService()

  "generateClientSecret" should {
    "create new ClientSecret object using UUID for secret value" in {
      val generatedClientSecret = underTest.generateClientSecret()

      generatedClientSecret.id.isEmpty should be (false)
      generatedClientSecret.secret.isEmpty should be (false)
      generatedClientSecret.name.length should be (36)
      generatedClientSecret.name take 32 should be ("•" * 32)
      generatedClientSecret.name.slice(32, 36) should be (generatedClientSecret.secret takeRight 4)
    }
  }
}
