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

package uk.gov.hmrc.thirdpartyapplication.metrics

import com.google.inject.Singleton

import javax.inject.Inject
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.mongo.metrix.MetricSource

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class ApplicationCount @Inject()(applicationRepository: ApplicationRepository) extends MetricSource with ApplicationLogger {

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    logger.info(s"[METRIC]: Start - ApplicationCount")
    val result = applicationRepository.count

    result.onComplete({
      case Success(v) =>
        logger.info(s"[METRIC] Future.success - ApplicationCount.metrics() - number of applications are: $v")

      case Failure(e) =>
        logger.info(s"[METRIC] Future.failure - ApplicationCount.metrics() - error is: ${e.toString}")
    })
    logger.info(s"[METRIC]: Finish - ApplicationCount")
    result.map(applicationCount => Map("applicationCount" -> applicationCount))

//    applicationRepository.count.map(applicationCount => {
//      logger.info(s"[METRIC] Application Count: $applicationCount")
//      Map("applicationCount" -> applicationCount)
//    })
  }
}
