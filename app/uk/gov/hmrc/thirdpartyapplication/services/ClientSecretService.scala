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

package uk.gov.hmrc.thirdpartyapplication.services

import java.time.{Clock, Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, blocking}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientSecretsHashingConfig}
import uk.gov.hmrc.apiplatform.modules.common.domain.services.ClockNow
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, SimpleTimer}
import uk.gov.hmrc.apiplatform.modules.crypto.services.SecretsHashingService
import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientSecretData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.common.services.TimedValue
// API-7200 // mport java.util.concurrent.Executors

@Singleton
class ClientSecretService @Inject() (config: ClientSecretsHashingConfig, applicationRepository: ApplicationRepository, val clock: Clock)(implicit ec: ExecutionContext)
    extends SecretsHashingService with ApplicationLogger with SimpleTimer with ClockNow {

  // API-7200 // implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
  override val workFactor = config.workFactor

  def clientSecretIsValid(applicationId: ApplicationId, secret: String, candidateClientSecrets: Seq[ClientSecretData]): Future[Option[ClientSecretData]] = {
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
                                    .find(clientSecret => checkAgainstHash(secret, clientSecret.hashedSecret))
          _                     = if (requiresRehash(matchingClientSecret.hashedSecret)) {
                                    // Fire and forget out of chain of execution (not map/flatMap'ed)
                                    applicationRepository.updateClientSecretHash(applicationId, matchingClientSecret.id, timedHashSecret(secret))
                                  }
        } yield matchingClientSecret
      }
    }
  }

  def timedHashSecret(secretValue: String): String = {
    val timedValue: TimedValue[String] = timeThis(() => hashSecret(secretValue))

    logger.info(
      s"[ClientSecretService] Hashing Secret with Work Factor of [${workFactor}] took [${timedValue.duration.toString()}]"
    )

    timedValue.value
  }

  def lastUsedOrdering: (ClientSecretData, ClientSecretData) => Boolean = {
    val oldEpochDateTime = Instant.ofEpochMilli(0).atOffset(ZoneOffset.UTC).toLocalDateTime
    (first, second) => first.lastAccess.getOrElse(oldEpochDateTime).isAfter(second.lastAccess.getOrElse(oldEpochDateTime))
  }
}
