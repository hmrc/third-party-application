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

object TermsOfUseSearch {

  def fromQueryString(queryString: Map[String, Seq[String]]): TermsOfUseSearch = {

    def filters = queryString
      .map {
        case (key, values) =>
          key match {
            case "search"                         => TermsOfUseTextSearchFilter(values.head)
            case "status"                         => TermsOfUseStatusFilter(values)
            case _                                => None // ignore anything that isn't a search filter
          }
      }
      .flatten
      .filter(searchFilter => searchFilter.isDefined)
      .flatten
      .toList

    def searchText     = queryString.getOrElse("search", List.empty).headOption
    new TermsOfUseSearch(filters, searchText)
  }
}


sealed trait TermsOfUseSearchFilter

sealed trait TermsOfUseStatusFilter   extends TermsOfUseSearchFilter
case object EmailSent                 extends TermsOfUseStatusFilter
case object ReminderEmailSent         extends TermsOfUseStatusFilter
case object Overdue                   extends TermsOfUseStatusFilter
case object Warnings                  extends TermsOfUseStatusFilter
case object Failed                    extends TermsOfUseStatusFilter
case object TermsOfUseV2WithWarnings  extends TermsOfUseStatusFilter
case object TermsOfUseV2              extends TermsOfUseStatusFilter

case object TermsOfUseStatusFilter extends TermsOfUseStatusFilter {

  def apply(values: Seq[String]): Seq[Option[TermsOfUseStatusFilter]] = {
    values.map(value => value match {
      case "EMAIL_SENT"                    => Some(EmailSent)
      case "REMINDER_EMAIL_SENT"           => Some(ReminderEmailSent)
      case "OVERDUE"                       => Some(Overdue)
      case "WARNINGS"                      => Some(Warnings)
      case "FAILED"                        => Some(Failed)
      case "TERMS_OF_USE_V2_WITH_WARNINGS" => Some(TermsOfUseV2WithWarnings)
      case "TERMS_OF_USE_V2"               => Some(TermsOfUseV2)
      case _                               => None
    } )
  }
}

sealed trait TermsOfUseTextSearchFilter     extends TermsOfUseSearchFilter
case object TermsOfUseTextSearch extends TermsOfUseTextSearchFilter

case object TermsOfUseTextSearchFilter extends TermsOfUseTextSearchFilter {

  def apply(value: String): Seq[Option[TermsOfUseTextSearchFilter]] = {
    value match {
      case _ if value.nonEmpty => Seq(Some(TermsOfUseTextSearch))
      case _                   => Seq(None)
    }
  }
}

sealed trait TermsOfUseSort
case object AppNameAscending          extends TermsOfUseSort
case object AppNameDescending         extends TermsOfUseSort
case object StatusAscending           extends TermsOfUseSort
case object StatusDescending          extends TermsOfUseSort
case object LastUpdatedDateAscending  extends TermsOfUseSort
case object LastUpdatedDateDescending extends TermsOfUseSort
case object TermsOfUseNoSorting       extends TermsOfUseSort
