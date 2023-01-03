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

package uk.gov.hmrc.thirdpartyapplication.controllers

import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import java.util.Base64
import java.nio.charset.StandardCharsets

trait AuthConfigSetup {

  val authorisationKey = "Foo"

  def base64Encode(stringToEncode: String): String = new String(Base64.getEncoder.encode(stringToEncode.getBytes), StandardCharsets.UTF_8)

  def provideAuthConfig(): AuthControlConfig = {
    AuthControlConfig(true, false, authorisationKey)
  }
}

trait SandboxAuthSetup extends AuthConfigSetup { 
  override def provideAuthConfig(): AuthControlConfig = AuthControlConfig(true, true, authorisationKey)
}

trait ProductionAuthSetup extends AuthConfigSetup
