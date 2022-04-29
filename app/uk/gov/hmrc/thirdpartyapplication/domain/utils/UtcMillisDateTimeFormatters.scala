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

package uk.gov.hmrc.thirdpartyapplication.domain.utils

import play.api.libs.json.EnvWrites

import java.time.{Instant, LocalDateTime, ZoneOffset}

trait UtcMillisDateTimeFormatters extends EnvWrites {
  import play.api.libs.json._

  implicit val dateTimeWriter: Writes[LocalDateTime] = LocalDateTimeEpochMilliWrites

  implicit val dateTimeReader: Reads[LocalDateTime] = new Reads[LocalDateTime] {
    def reads(json: JsValue): JsResult[LocalDateTime] = json match {
      case JsNumber(n) => JsSuccess( Instant.ofEpochMilli(n.toLong).
        atZone(ZoneOffset.UTC).toLocalDateTime)
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.time"))))
    }
  }
  implicit val dateTimeFormat: Format[LocalDateTime] = Format(dateTimeReader, dateTimeWriter)
}

object UtcMillisDateTimeFormatters extends UtcMillisDateTimeFormatters
