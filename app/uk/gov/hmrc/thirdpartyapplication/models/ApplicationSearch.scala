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

class ApplicationSearch(var pageNumber: Int, var pageSize: Int, var filters: Seq[ApplicationSearchFilter]) {

  def this(request: Request[AnyContent]) {
    this(
      request.getQueryString("page").getOrElse("1").toInt,
      request.getQueryString("pageSize").getOrElse("100").toInt,
      request.queryString
        .map {
          case (key, value) => {
            // 'value' is a Seq, but we should only ever have one of each, so just take the head
            key match {
              case "apiSubscriptions" => APISubscriptionFilter(value.head)
              case "status" => ApplicationStatusFilter(value.head)
              case "termsOfUse" => TermsOfUseStatusFilter(value.head)
              case "accessType" => AccessTypeFilter(value.head)
              case _ => None // ignore anything that isn't a search filter
            }
          }
        }
        .filter(searchFilter => searchFilter.isDefined)
        .flatten
        .toSeq)
  }
}

sealed trait ApplicationSearchFilter

sealed trait SearchTextFilter extends ApplicationSearchFilter {
  var text: String
}
//case object SearchTextFilter extends SearchTextFilter {
//  def apply(value: String): SearchTextFilter = {
//    this.text = value
//    this
//  }
//}

sealed trait APISubscriptionFilter extends ApplicationSearchFilter
case object OneOrMoreAPISubscriptions extends APISubscriptionFilter
case object NoAPISubscriptions extends APISubscriptionFilter

case object APISubscriptionFilter extends APISubscriptionFilter {
  def apply(value: String): Option[APISubscriptionFilter] = {
    value match {
      case "ANYSUB" => Some(OneOrMoreAPISubscriptions)
      case "NOSUB" => Some(NoAPISubscriptions)
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
      case "TOU_NOT_ACCEPTED" => Some(TermsOfUseNotAccepted)
      case "TOU_ACCEPTED" => Some(TermsOfUseAccepted)
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
      case "ACCESS_TYPE_STANDARD" => Some(StandardAccess)
      case "ACCESS_TYPE_ROPC" => Some(ROPCAccess)
      case "ACCESS_TYPE_PRIVILEGED" => Some(PrivilegedAccess)
      case _ => None
    }
  }
}
