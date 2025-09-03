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

package uk.gov.hmrc.thirdpartyapplication.controllers.query

import cats.syntax.option._

sealed trait Sorting

object Sorting {
  case object NameAscending         extends Sorting
  case object NameDescending        extends Sorting
  case object SubmittedAscending    extends Sorting
  case object SubmittedDescending   extends Sorting
  case object LastUseDateAscending  extends Sorting
  case object LastUseDateDescending extends Sorting
  case object NoSorting             extends Sorting

  def apply(text: String): Option[Sorting] = {
    text match {
      case "NAME_ASC"       => NameAscending.some
      case "NAME_DESC"      => NameDescending.some
      case "SUBMITTED_ASC"  => SubmittedAscending.some
      case "SUBMITTED_DESC" => SubmittedDescending.some
      case "LAST_USE_ASC"   => LastUseDateAscending.some
      case "LAST_USE_DESC"  => LastUseDateDescending.some
      case "NO_SORT"        => NoSorting.some
      case _                => None
    }
  }
}
