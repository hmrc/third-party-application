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

import java.time.LocalDateTime

import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ClientId
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredClientSecret

case class StoredToken(
    clientId: ClientId,
    accessToken: String,
    clientSecrets: List[StoredClientSecret] = List(),
    lastAccessTokenUsage: Option[LocalDateTime] = None
  )

object StoredToken {
  import play.api.libs.json.Json

  implicit val dateFormat = MongoJavatimeFormats.localDateTimeFormat
  implicit val format     = Json.format[StoredToken]
}