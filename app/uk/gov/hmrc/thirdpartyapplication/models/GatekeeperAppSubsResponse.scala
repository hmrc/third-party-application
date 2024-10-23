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

package uk.gov.hmrc.thirdpartyapplication.models.db

import java.time.Instant

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApiIdentifier, ApplicationId}
import play.api.libs.json.Writes
import play.api.libs.json.Json

case class GatekeeperAppSubsResponse(
    id: ApplicationId,
    name: String,
    lastAccess: Option[Instant],
    apiIdentifiers: Set[ApiIdentifier]
  )

object GatekeeperAppSubsResponse {
  implicit val writes: Writes[GatekeeperAppSubsResponse] = Json.writes[GatekeeperAppSubsResponse]
}