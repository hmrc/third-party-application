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
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationCount @Inject()(val applicationRepository: ApplicationRepository) extends MetricSource with ApplicationLogger {
  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    applicationRepository.count.map(applicationCount => {
      logger.info(s"[METRIC] Application Count: $applicationCount")
      Map("applicationCount" -> applicationCount)
    })
}
