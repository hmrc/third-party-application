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

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import javax.inject.Inject
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

class UpdateDeleteAllowedAttributeService @Inject()(applicationRepository: ApplicationRepository) extends ApplicationLogger {

  def updateDeleteAllowedAttribute(): Unit = {
    logger.info(s"About to run Startup job to add allowAutoDelete flag and set to true")

    val applicationsUpdated = applicationRepository.updateAllApplicationsWithDeleteAllowed

    logger.info(s"Added / Updated allowAutoDelete flag to $applicationsUpdated Applications")
  }

  updateDeleteAllowedAttribute
}
