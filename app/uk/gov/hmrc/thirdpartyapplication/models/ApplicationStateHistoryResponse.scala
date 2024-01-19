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

package uk.gov.hmrc.thirdpartyapplication.models

import java.time.Instant

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State

case class ApplicationStateHistoryResponse(applicationId: ApplicationId, appName: String, journeyVersion: Int, stateHistory: List[ApplicationStateHistoryResponse.Item])

object ApplicationStateHistoryResponse {
  case class Item(state: State, timestamp: Instant)

  import play.api.libs.json.Json
  import play.api.libs.json.OFormat

  object Item {
    implicit val format: OFormat[Item] = Json.format[Item]
  }

  implicit val formatApplicationStateHistory: OFormat[ApplicationStateHistoryResponse] = Json.format[ApplicationStateHistoryResponse]
}
