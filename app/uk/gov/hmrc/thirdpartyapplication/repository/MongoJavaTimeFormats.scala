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

package uk.gov.hmrc.thirdpartyapplication.repository

import play.api.libs.json.{Format, Reads, Writes, __}

import java.time.{Instant, LocalDateTime, ZoneOffset, ZonedDateTime}


trait MongoJavaTimeFormats {
  outer =>

  final val localDateTimeReads: Reads[LocalDateTime] =
    /*Reads.at[Long](__ \ "$date" )
      .map(dateTime =>
        Instant.ofEpochMilli(dateTime).atZone(ZoneOffset.UTC).toLocalDateTime)*/

    Reads.at[String](__ \ "$date" )
      .map(dateTime =>ZonedDateTime.parse(dateTime).toLocalDateTime.atZone(ZoneOffset.UTC).toLocalDateTime)

  final val localDateTimeWrites: Writes[LocalDateTime] =
    /*Writes.at[Long](__ \ "$date" )
      .contramap(x => x.toInstant(ZoneOffset.UTC).toEpochMilli)*/

    Writes.at[String](__ \ "$date" )
      .contramap(dateTime => dateTime.atZone(ZoneOffset.UTC).toString)

  final val localDateTimeFormat: Format[LocalDateTime] =
    Format(localDateTimeReads, localDateTimeWrites)

  trait Implicits {
    implicit val jatLocalDateTimeFormat: Format[LocalDateTime] = outer.localDateTimeFormat
  }

  object Implicits extends Implicits
}

object MongoJavaTimeFormats extends MongoJavaTimeFormats

