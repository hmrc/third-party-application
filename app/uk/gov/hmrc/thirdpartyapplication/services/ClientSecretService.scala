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

import com.github.t3hnar.bcrypt._
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.Logger
import uk.gov.hmrc.thirdpartyapplication.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.services.ClientSecretService.maskSecret
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}

@Singleton
class ClientSecretService @Inject()(config: ClientSecretServiceConfig) {

  def clientSecretValueGenerator: () => String = UUID.randomUUID().toString

  def generateClientSecret(): ClientSecret = {
    val secretValue = clientSecretValueGenerator()

    ClientSecret(
      name = maskSecret(secretValue),
      secret = secretValue,
      hashedSecret = hashSecret(secretValue))
  }

  def hashSecret(secret: String): String = {
    /*
     * Measure the time it takes to perform the hashing process - need to ensure we tune the work factor so that we don't introduce too much delay for
     * legitimate users.
     */
    val startTime = DateTimeUtils.now
    val hashedValue = secret.bcrypt(config.hashFunctionWorkFactor)
    val endTime = DateTimeUtils.now

    Logger.info(
      s"[ClientSecretService] Hashing Secret with Work Factor of [${config.hashFunctionWorkFactor}] took [${endTime.getMillis - startTime.getMillis}ms]")

    hashedValue
  }

  def clientSecretIsValid(secret: String, candidateClientSecrets: Seq[ClientSecret]): Future[Option[ClientSecret]] = {
    Future {
      blocking {
        candidateClientSecrets
          .sortWith(lastUsedOrdering)
          .find(clientSecret => {
            secret.isBcryptedSafe(clientSecret.hashedSecret) match {
              case Success(result) => result
              case Failure(_) => false
            }
          })
      }
    }
  }

  def lastUsedOrdering: (ClientSecret, ClientSecret) => Boolean =
    (first, second) => first.lastAccess.getOrElse(new DateTime(0)).isAfter(second.lastAccess.getOrElse(new DateTime(0)))
}

object ClientSecretService {

  def maskSecret(secret: String): String = {
    val SecretMask = "••••••••••••••••••••••••••••••••"
    val SecretLastDigitsLength = 4
    s"$SecretMask${secret.takeRight(SecretLastDigitsLength)}"
  }

}

case class ClientSecretServiceConfig(hashFunctionWorkFactor: Int)