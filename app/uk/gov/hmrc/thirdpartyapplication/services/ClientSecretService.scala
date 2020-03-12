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

package uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import uk.gov.hmrc.thirdpartyapplication.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService.maskSecret
import com.github.t3hnar.bcrypt._
import javax.inject.{Inject, Singleton}

@Singleton
class ClientSecretService @Inject()(config: ClientSecretServiceConfig) {

  def clientSecretValueGenerator: () => String = UUID.randomUUID().toString

  def generateClientSecret(): ClientSecret = {
    val secretValue = clientSecretValueGenerator()

    ClientSecret(
      name = maskSecret(secretValue),
      secret = secretValue,
      hashedSecret = Some(secretValue.bcrypt(config.hashFunctionWorkFactor)))
  }

}

object ClientSecretService {

  def maskSecret(secret: String): String = {
    val SecretMask = "••••••••••••••••••••••••••••••••"
    val SecretLastDigitsLength = 4
    s"$SecretMask${secret.takeRight(SecretLastDigitsLength)}"
  }

}

case class ClientSecretServiceConfig(hashFunctionWorkFactor: Int)