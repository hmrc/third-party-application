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

import play.api.libs.json.{Format, Json, Reads}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifier
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

case class ApplicationWithSubscriptions(
    id: ApplicationId,
    name: String,
    lastAccess: Option[LocalDateTime],
    apiIdentifiers: Set[ApiIdentifier]
  )

object ApplicationWithSubscriptions {
  implicit val dateFormat: Format[LocalDateTime]          = MongoJavatimeFormats.localDateTimeFormat
  implicit val reads: Reads[ApplicationWithSubscriptions] = Json.reads[ApplicationWithSubscriptions]
}