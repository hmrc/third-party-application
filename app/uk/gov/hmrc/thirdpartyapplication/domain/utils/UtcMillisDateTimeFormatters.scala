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

package uk.gov.hmrc.thirdpartyapplication.domain.utils

trait UtcMillisDateTimeFormatters {
  import org.joda.time.{DateTimeZone, DateTime}
  import play.api.libs.json._
  import play.api.libs.json.JodaWrites._

  implicit val dateTimeWriter: Writes[DateTime] = JodaDateTimeNumberWrites

  implicit val dateTimeReader: Reads[DateTime] = new Reads[DateTime] {
    def reads(json: JsValue): JsResult[DateTime] = json match {
      case JsNumber(n) => JsSuccess(new DateTime(n.toLong, DateTimeZone.UTC))
      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.time"))))
    }
  }

  implicit val dateTimeFormat: Format[DateTime] = Format(dateTimeReader, dateTimeWriter)
}

object UtcMillisDateTimeFormatters extends UtcMillisDateTimeFormatters
