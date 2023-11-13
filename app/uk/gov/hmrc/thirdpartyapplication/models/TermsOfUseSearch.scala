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

package uk.gov.hmrc.thirdpartyapplication.models

final case class TermsOfUseSearch(
    filters: List[TermsOfUseSearchFilter] = List.empty,
    textToSearch: Option[String] = None,
    pageNumber: Int = 1,
    pageSize: Int = Int.MaxValue,
    sort: TermsOfUseSort = TermsOfUseNoSorting
)

sealed trait TermsOfUseSearchFilter

sealed trait TermsOfUseStatusFilter   extends TermsOfUseSearchFilter
case object EmailSent                 extends TermsOfUseStatusFilter
case object ReminderEmailSent         extends TermsOfUseStatusFilter
case object Overdue                   extends TermsOfUseStatusFilter
case object Warnings                  extends TermsOfUseStatusFilter
case object Failed                    extends TermsOfUseStatusFilter
case object TermsOfUseV2              extends TermsOfUseStatusFilter
case object TermsOfUseV2WithWarnings  extends TermsOfUseStatusFilter

sealed trait TermsOfUseTextSearchFilter     extends TermsOfUseSearchFilter
case object TermsOfUseTextSearch extends TermsOfUseTextSearchFilter

case object TermsOfUseTextSearchFilter extends TermsOfUseTextSearchFilter {

  def apply(value: String): Option[TermsOfUseTextSearchFilter] = {
    value match {
      case _ if value.nonEmpty => Some(TermsOfUseTextSearch)
      case _                   => None
    }
  }
}

// sealed trait TermsOfUseTextSearchFilter extends TermsOfUseSearchFilter
// case object TermsOfUseTextSearch        extends TermsOfUseTextSearchFilter

sealed trait TermsOfUseSort
case object AppNameAscending          extends TermsOfUseSort
case object AppNameDescending         extends TermsOfUseSort
case object StatusAscending           extends TermsOfUseSort
case object StatusDescending          extends TermsOfUseSort
case object LastUpdatedDateAscending  extends TermsOfUseSort
case object LastUpdatedDateDescending extends TermsOfUseSort
case object TermsOfUseNoSorting       extends TermsOfUseSort
