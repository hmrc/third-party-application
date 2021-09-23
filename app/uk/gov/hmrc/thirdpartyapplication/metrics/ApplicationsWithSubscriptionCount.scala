/*
 * Copyright 2021 HM Revenue & Customs
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

import com.google.inject.Singleton
import javax.inject.Inject
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationLogger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class ApplicationsWithSubscriptionCount @Inject()(val applicationRepository: ApplicationRepository) extends MetricSource with ApplicationLogger {
  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    logger.info("Starting - ApplicationsWithSubscriptionCount.metrics()")
    // TODO Need to handle Application with zero subscriptions
    val result = applicationRepository.getApplicationWithSubscriptionCount()
    result
      .onComplete({
        case Success(v) =>
          logger.info(s"Future.success - ApplicationsWithSubscriptionCount.metrics() - number of applications are: ${v.keys.size}")

        case Failure(e) =>
          logger.info(s"Future.failure - ApplicationsWithSubscriptionCount.metrics() - error is: ${e.toString}")
      })
    logger.info("Finish - ApplicationsWithSubscriptionCount.metrics()")
    result
  }


}
