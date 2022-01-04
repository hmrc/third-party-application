/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationLogger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.util.{Failure, Success}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import org.joda.time.DateTimeZone

@Singleton
class ClientSecretService @Inject()(applicationRepository: ApplicationRepository, config: ClientSecretServiceConfig) extends ApplicationLogger {

  def clientSecretValueGenerator: () => String = UUID.randomUUID().toString

  def generateClientSecret(): (ClientSecret, String) = {
    val secretValue = clientSecretValueGenerator()

    (ClientSecret(name = secretValue.takeRight(4), hashedSecret = hashSecret(secretValue)), secretValue)
  }

  def hashSecret(secret: String): String = {
    /*
     * Measure the time it takes to perform the hashing process - need to ensure we tune the work factor so that we don't introduce too much delay for
     * legitimate users.
     */
    val startTime = DateTimeUtils.now
    val hashedValue = secret.bcrypt(config.hashFunctionWorkFactor)
    val endTime = DateTimeUtils.now

    logger.info(
      s"[ClientSecretService] Hashing Secret with Work Factor of [${config.hashFunctionWorkFactor}] took [${endTime.getMillis - startTime.getMillis}ms]")

    hashedValue
  }

  def clientSecretIsValid(applicationId: ApplicationId, secret: String, candidateClientSecrets: Seq[ClientSecret]): Future[Option[ClientSecret]] = {
    /*
     * *** WARNING ***
     * This function is called every time an OAuth2 token is issued, and is therefore crucially important to the overall performance of the API Platform.
     *
     * As we use bcrypt to hash the secrets, there is a (deliberate) slowness to calculating the hash, and we may need to perform multiple comparisons for a
     * given secret (applications can have up to 5 secrets associated), we can run in to some pretty major performance issues if we're not careful.
     *
     * ANY CHANGES TO THIS FUNCTION NEED TO BE THOROUGHLY PERFORMANCE TESTED
     *
     * Whilst it may look like the function would benefit from some parallelism, the instances running on the current MDTP platform only have 1 or 2 cores.
     * Spinning up upto 5 threads per token request in this environment starts to degrade performance pretty quickly. This may change if Future Platform
     * provides instances with more cores, but for now we should stick with doing things sequentially.
     */
    Future {
      blocking {
        for {
          matchingClientSecret <- candidateClientSecrets
            .sortWith(lastUsedOrdering) // Assuming most clients use the same secret every time, we should match on the first comparison most of the time
            .find(clientSecret => {
              secret.isBcryptedSafe(clientSecret.hashedSecret) match {
                case Success(result) => result
                case Failure(_) => false
              }
            })
          _ = if (requiresRehash(matchingClientSecret.hashedSecret)) {
            applicationRepository.updateClientSecretHash(applicationId, matchingClientSecret.id, hashSecret(secret))
          }
        } yield matchingClientSecret
      }
    }
  }

  def lastUsedOrdering: (ClientSecret, ClientSecret) => Boolean =
    (first, second) => first.lastAccess.getOrElse(new DateTime(0, DateTimeZone.UTC)).isAfter(second.lastAccess.getOrElse(new DateTime(0, DateTimeZone.UTC)))

  def requiresRehash(hashedSecret: String): Boolean = workFactorOfHash(hashedSecret) != config.hashFunctionWorkFactor

  def workFactorOfHash(hashedSecret: String): Int = hashedSecret.split("\\$")(2).toInt
}

case class ClientSecretServiceConfig(hashFunctionWorkFactor: Int)