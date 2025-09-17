/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.applications.query.domain.models

sealed trait Sorting

object Sorting {
  case object NameAscending         extends Sorting
  case object NameDescending        extends Sorting
  case object SubmittedAscending    extends Sorting
  case object SubmittedDescending   extends Sorting
  case object LastUseDateAscending  extends Sorting
  case object LastUseDateDescending extends Sorting
  case object NoSorting             extends Sorting

  val pairing = List(
    ("NAME_ASC", NameAscending),
    ("NAME_DESC", NameDescending),
    ("SUBMITTED_ASC", SubmittedAscending),
    ("SUBMITTED_DESC", SubmittedDescending),
    ("LAST_USE_ASC", LastUseDateAscending),
    ("LAST_USE_DESC", LastUseDateDescending),
    ("NO_SORT", NoSorting)
  )

  def asText(sort: Sorting): String =
    pairing
      .find(p => p._2 == sort)
      .map(_._1)
      .get // Safe in this case

  def apply(text: String): Option[Sorting] =
    pairing.filter {
      case (n, v) => n == text
    }
      .headOption
      .map(_._2)
}
