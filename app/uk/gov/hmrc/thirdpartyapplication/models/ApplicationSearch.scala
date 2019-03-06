/*
 * Copyright 2019 HM Revenue & Customs
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

import play.api.mvc.{AnyContent, Request}

class ApplicationSearch(var pageNumber: Int,
                        var pageSize: Int,
                        var filters: Seq[ApplicationSearchFilter],
                        var textToSearch: String = "",
                        var apiContext: String = "",
                        var apiVersion: String = "") {

  def this(filters: Seq[ApplicationSearchFilter], apiContext: String, apiVersion: String) {
    this(ApplicationSearch.DefaultPageNumber, ApplicationSearch.DefaultPageSize, filters, "", apiContext, apiVersion)
  }

  def this(filters: Seq[ApplicationSearchFilter], textToSearch: String) {
    this(ApplicationSearch.DefaultPageNumber, ApplicationSearch.DefaultPageSize, filters, textToSearch)
  }

  def this(filters: Seq[ApplicationSearchFilter]) {
    this(filters, "")
  }

  def this() {
    this(Seq.empty)
  }
}

object ApplicationSearch {
  val PageNumberParameterName = "page"
  val PageSizeParameterName = "pageSize"

  // Set paging defaults that mean we'll get everything back (so that a search specifying only filters will get all relevant results)
  val DefaultPageNumber: Int = 1
  val DefaultPageSize: Int = Int.MaxValue

  def fromRequest(request: Request[AnyContent]): ApplicationSearch = {
    def pageNumber =
      if (request.getQueryString(PageNumberParameterName).isDefined) request.getQueryString(PageNumberParameterName).get.toInt else DefaultPageNumber
    def pageSize =
      if (request.getQueryString(PageSizeParameterName).isDefined) request.getQueryString(PageSizeParameterName).get.toInt else DefaultPageSize

    def filters = request.queryString
      .map {
        case (key, value) =>
          // 'value' is a Seq, but we should only ever have one of each, so just take the head
          key match {
            case "search" => TextSearchFilter(value.head)
            case "apiSubscription" => APISubscriptionFilter(value.head)
            case "status" => ApplicationStatusFilter(value.head)
            case "termsOfUse" => TermsOfUseStatusFilter(value.head)
            case "accessType" => AccessTypeFilter(value.head)
            case _ => None // ignore anything that isn't a search filter
          }
      }
      .filter(searchFilter => searchFilter.isDefined)
      .flatten
      .toSeq

    def searchText = if(filters.contains(ApplicationTextSearch)) request.getQueryString("search").getOrElse("") else ""
    def apiSubscription = if(filters.contains(SpecificAPISubscription)) request.getQueryString("apiSubscription").getOrElse("") else ""
    def apiVersion = if(filters.contains(SpecificAPISubscription)) request.getQueryString("apiVersion").getOrElse("") else ""

    new ApplicationSearch(pageNumber, pageSize, filters, searchText, apiSubscription, apiVersion)
  }
}

sealed trait ApplicationSearchFilter

sealed trait TextSearchFilter extends ApplicationSearchFilter
case object ApplicationTextSearch extends TextSearchFilter

case object TextSearchFilter extends TextSearchFilter {
  def apply(value: String): Option[TextSearchFilter] = {
    value match {
      case _ if !value.isEmpty => Some(ApplicationTextSearch)
      case _ => None
    }
  }
}

sealed trait APISubscriptionFilter extends ApplicationSearchFilter
case object OneOrMoreAPISubscriptions extends APISubscriptionFilter
case object NoAPISubscriptions extends APISubscriptionFilter
case object SpecificAPISubscription extends APISubscriptionFilter

case object APISubscriptionFilter extends APISubscriptionFilter {
  def apply(value: String): Option[APISubscriptionFilter] = {

    value match {
      case "ANY" => Some(OneOrMoreAPISubscriptions)
      case "NONE" => Some(NoAPISubscriptions)
      case _ if !value.isEmpty => Some(SpecificAPISubscription) // If the value of apiSubscription is something else, assume we are searching for a specific API
      case _ => None
    }
  }
}

sealed trait StatusFilter extends ApplicationSearchFilter
case object Created extends StatusFilter
case object PendingGatekeeperCheck extends StatusFilter
case object PendingSubmitterVerification extends StatusFilter
case object Active extends StatusFilter

case object ApplicationStatusFilter extends StatusFilter {
  def apply(value: String): Option[StatusFilter] = {
    value match {
      case "CREATED" => Some(Created)
      case "PENDING_GATEKEEPER_CHECK" => Some(PendingGatekeeperCheck)
      case "PENDING_SUBMITTER_VERIFICATION" => Some(PendingSubmitterVerification)
      case "ACTIVE" => Some(Active)
      case _ => None
    }
  }
}
sealed trait TermsOfUseFilter extends ApplicationSearchFilter
case object TermsOfUseNotAccepted extends TermsOfUseFilter
case object TermsOfUseAccepted extends TermsOfUseFilter

case object TermsOfUseStatusFilter extends TermsOfUseFilter {
  def apply(value: String): Option[TermsOfUseFilter] = {
    value match {
      case "NOT_ACCEPTED" => Some(TermsOfUseNotAccepted)
      case "ACCEPTED" => Some(TermsOfUseAccepted)
      case _ => None
    }
  }
}

sealed trait AccessTypeFilter extends ApplicationSearchFilter
case object StandardAccess extends AccessTypeFilter
case object ROPCAccess extends AccessTypeFilter
case object PrivilegedAccess extends AccessTypeFilter

case object AccessTypeFilter extends AccessTypeFilter {
  def apply(value: String): Option[AccessTypeFilter] = {
    value match {
      case "STANDARD" => Some(StandardAccess)
      case "ROPC" => Some(ROPCAccess)
      case "PRIVILEGED" => Some(PrivilegedAccess)
      case _ => None
    }
  }
}
