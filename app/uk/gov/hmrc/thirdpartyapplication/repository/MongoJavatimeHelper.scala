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

package uk.gov.hmrc.thirdpartyapplication.repository

import java.time.{Instant, LocalDateTime, ZoneOffset}

import play.api.libs.json._

object MongoJavatimeHelper {
  def asJsValue(ldt: LocalDateTime): JsValue = Json.obj("$date" -> Json.obj("$numberLong" -> JsString(ldt.toInstant(ZoneOffset.UTC).toEpochMilli().toString())))
  def asJsValue(instant: Instant): JsValue   = Json.obj("$date" -> Json.obj("$numberLong" -> JsString(instant.toEpochMilli().toString())))
}