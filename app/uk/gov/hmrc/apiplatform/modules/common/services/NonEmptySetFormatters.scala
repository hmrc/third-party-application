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

package uk.gov.hmrc.apiplatform.modules.common.services

import play.api.libs.json._
import cats.data.{NonEmptySet => NES}
import cats.kernel.Order

trait NonEmptySetFormatters {

  implicit def nesReads[A](implicit r: Reads[A], o: Order[A]): Reads[NES[A]] =
    Reads
      .of[List[A]]
      .collect(
        JsonValidationError("expected at least one value but got nothing")
      ) {
        case head :: tail => NES.of(head, tail: _*)
      }

  implicit def nesWrites[A](implicit w: Writes[A]): Writes[NES[A]] =
    Writes
      .of[List[A]]
      .contramap(_.toNonEmptyList.toList)
}

object NonEmptySetFormatters extends NonEmptySetFormatters
