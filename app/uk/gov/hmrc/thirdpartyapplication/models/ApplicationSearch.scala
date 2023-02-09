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

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Aggregates
import org.mongodb.scala.model.Filters._

import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._

// scalastyle:off number.of.types

case class ApplicationSearch(
    pageNumber: Int = 1,
    pageSize: Int = Int.MaxValue,
    filters: List[ApplicationSearchFilter] = List.empty,
    textToSearch: Option[String] = None,
    apiContext: Option[ApiContext] = None,
    apiVersion: Option[ApiVersion] = None,
    sort: ApplicationSort = SubmittedAscending,
    includeDeleted: Boolean = false
  ) {
  def hasSubscriptionFilter()            = filters.exists(filter => filter.isInstanceOf[APISubscriptionFilter])
  def hasSpecificApiSubscriptionFilter() = filters.exists(filter => filter.isInstanceOf[SpecificAPISubscription.type])
}

object ApplicationSearch {

  def fromQueryString(queryString: Map[String, Seq[String]]): ApplicationSearch = {
    def pageNumber = queryString.getOrElse("page", List.empty).headOption.getOrElse("1").toInt
    def pageSize   = queryString.getOrElse("pageSize", List.empty).headOption.getOrElse(Int.MaxValue.toString).toInt

    def filters = queryString
      .map {
        case (key, value) =>
          // 'value' is a Seq, but we should only ever have one of each, so just take the head
          key match {
            case "search"                         => TextSearchFilter(value.head)
            case "apiSubscription"                => APISubscriptionFilter(value.head)
            case "status"                         => ApplicationStatusFilter(value.head)
            case "accessType"                     => AccessTypeFilter(value.head)
            case "lastUseBefore" | "lastUseAfter" => LastUseDateFilter(key, value.head)
            case _                                => None // ignore anything that isn't a search filter
          }
      }
      .filter(searchFilter => searchFilter.isDefined)
      .flatten
      .toList

    def searchText     = queryString.getOrElse("search", List.empty).headOption
    def apiContext     = queryString.getOrElse("apiSubscription", List.empty).headOption.flatMap(_.split("--").headOption.map(ApiContext(_)))
    def apiVersion     = queryString.getOrElse("apiSubscription", List.empty).headOption.flatMap(_.split("--").lift(1).map(ApiVersion(_)))
    def sort           = ApplicationSort(queryString.getOrElse("sort", List.empty).headOption)
    def includeDeleted = queryString.getOrElse("includeDeleted", List.empty).headOption.getOrElse("false").toBoolean

    new ApplicationSearch(pageNumber, pageSize, filters, searchText, apiContext, apiVersion, sort, includeDeleted)
  }
}

sealed trait ApplicationSearchFilter

sealed trait TextSearchFilter     extends ApplicationSearchFilter
case object ApplicationTextSearch extends TextSearchFilter

case object TextSearchFilter extends TextSearchFilter {

  def apply(value: String): Option[TextSearchFilter] = {
    value match {
      case _ if value.nonEmpty => Some(ApplicationTextSearch)
      case _                   => None
    }
  }
}

sealed trait APISubscriptionFilter    extends ApplicationSearchFilter
case object OneOrMoreAPISubscriptions extends APISubscriptionFilter
case object NoAPISubscriptions        extends APISubscriptionFilter
case object SpecificAPISubscription   extends APISubscriptionFilter

case object APISubscriptionFilter extends APISubscriptionFilter {

  def apply(value: String): Option[APISubscriptionFilter] = {

    value match {
      case "ANY"               => Some(OneOrMoreAPISubscriptions)
      case "NONE"              => Some(NoAPISubscriptions)
      case _ if value.nonEmpty => Some(SpecificAPISubscription) // If the value of apiSubscription is something else, assume we are searching for a specific API
      case _                   => None
    }
  }
}

sealed trait StatusFilter                            extends ApplicationSearchFilter
case object Created                                  extends StatusFilter
case object PendingResponsibleIndividualVerification extends StatusFilter
case object PendingGatekeeperCheck                   extends StatusFilter
case object PendingSubmitterVerification             extends StatusFilter
case object Active                                   extends StatusFilter
case object WasDeleted                               extends StatusFilter
case object ExcludingDeleted                         extends StatusFilter

case object ApplicationStatusFilter extends StatusFilter {

  def apply(value: String): Option[StatusFilter] = {
    value match {
      case "CREATED"                                     => Some(Created)
      case "PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION" => Some(PendingResponsibleIndividualVerification)
      case "PENDING_GATEKEEPER_CHECK"                    => Some(PendingGatekeeperCheck)
      case "PENDING_SUBMITTER_VERIFICATION"              => Some(PendingSubmitterVerification)
      case "ACTIVE"                                      => Some(Active)
      case "DELETED"                                     => Some(WasDeleted)
      case "EXCLUDING_DELETED"                           => Some(ExcludingDeleted)
      case _                                             => None
    }
  }
}

sealed trait AccessTypeFilter extends ApplicationSearchFilter
case object StandardAccess    extends AccessTypeFilter
case object ROPCAccess        extends AccessTypeFilter
case object PrivilegedAccess  extends AccessTypeFilter

case object AccessTypeFilter extends AccessTypeFilter {

  def apply(value: String): Option[AccessTypeFilter] = {
    value match {
      case "STANDARD"   => Some(StandardAccess)
      case "ROPC"       => Some(ROPCAccess)
      case "PRIVILEGED" => Some(PrivilegedAccess)
      case _            => None
    }
  }
}

sealed trait LastUseDateFilter extends ApplicationSearchFilter

case class LastUseBeforeDate(lastUseDate: LocalDateTime) extends LastUseDateFilter {
  implicit val dateFormat = MongoJavatimeFormats.localDateTimeFormat

  def toMongoMatch: Bson = {
    Aggregates.filter(
      or(
        lte("lastAccess", Codecs.toBson(lastUseDate)),
        and(
          exists("lastAccess", false),
          lte("createdOn", lastUseDate)
        )
      )
    )
  }
}

case class LastUseAfterDate(lastUseDate: LocalDateTime) extends LastUseDateFilter {
  implicit val dateFormat = MongoJavatimeFormats.localDateTimeFormat

  def toMongoMatch: Bson = {
    Aggregates.filter(
      or(
        gte("lastAccess", Codecs.toBson(lastUseDate)),
        and(
          exists("lastAccess", false),
          gte("createdOn", lastUseDate)
        )
      )
    )
  }
}

case object LastUseDateFilter extends LastUseDateFilter {

  private def parseDateString(value: String) = {
    if (value.matches("""^\d{4}-\d{1,2}-\d{1,2}$""")) LocalDate.parse(value, DateTimeFormatter.ISO_DATE).atStartOfDay()
    else LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
  }

  def apply(queryType: String, value: String): Option[LastUseDateFilter] =
    queryType match {
      case "lastUseBefore" => Some(LastUseBeforeDate(parseDateString(value)))
      case "lastUseAfter"  => Some(LastUseAfterDate(parseDateString(value)))
      case _               => None
    }
}

sealed trait ApplicationSort
case object NameAscending         extends ApplicationSort
case object NameDescending        extends ApplicationSort
case object SubmittedAscending    extends ApplicationSort
case object SubmittedDescending   extends ApplicationSort
case object LastUseDateAscending  extends ApplicationSort
case object LastUseDateDescending extends ApplicationSort
case object NoSorting             extends ApplicationSort

object ApplicationSort extends ApplicationSort {

  def apply(value: Option[String]): ApplicationSort = value match {
    case Some("NAME_ASC")       => NameAscending
    case Some("NAME_DESC")      => NameDescending
    case Some("SUBMITTED_ASC")  => SubmittedAscending
    case Some("SUBMITTED_DESC") => SubmittedDescending
    case Some("LAST_USE_ASC")   => LastUseDateAscending
    case Some("LAST_USE_DESC")  => LastUseDateDescending
    case Some("NO_SORT")        => NoSorting
    case _                      => SubmittedAscending
  }
}

// scalastyle:on number.of.types
