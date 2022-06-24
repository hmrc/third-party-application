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

package uk.gov.hmrc.thirdpartyapplication.metrics

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.mongo.metrix.MetricSource

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class MissingMongoFields @Inject() (applicationRepository: ApplicationRepository)
    extends MetricSource
    with ApplicationLogger {

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    val counts: Future[(Int, Int)] = for {
      missingRateLimit      <- missingDocumentsWithField("rateLimitTier")
      missingLastAccessDate <- missingDocumentsWithField("lastAccess")
    } yield (missingRateLimit, missingLastAccessDate)

    counts.map(a => {
      Map("applicationsMissingRateLimitField" -> a._1, "applicationsMissingLastAccessDateField" -> a._2)
    })
  }

  private def missingDocumentsWithField(field: String)(implicit ec: ExecutionContext) = {
    val result = applicationRepository.documentsWithFieldMissing(field)
    result.onComplete({
      case Success(v) => logger.info(s"[METRIC] Success - MissingMongoFields - Number of documents with missing field $field is: $v")
      case Failure(e) => logger.info(s"[METRIC] Error - MissingMongoFields - Error whilst processing field $field: ${e.getMessage}")
    })
    result
  }
}
