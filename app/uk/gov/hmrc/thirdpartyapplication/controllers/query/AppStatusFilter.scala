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

sealed trait AppStatusFilter

object AppStatusFilter {
  case object Created                                  extends AppStatusFilter
  case object PendingResponsibleIndividualVerification extends AppStatusFilter
  case object PendingGatekeeperCheck                   extends AppStatusFilter
  case object PendingSubmitterVerification             extends AppStatusFilter
  case object Active                                   extends AppStatusFilter
  case object WasDeleted                               extends AppStatusFilter

  case object ExcludingDeleted                         extends AppStatusFilter
  case object Blocked                                  extends AppStatusFilter

  case object NoFiltering                              extends AppStatusFilter

  def apply(text: String): Option[AppStatusFilter] = {
    import cats.implicits._
    text match {
      case "CREATED"                                     => Created.some
      case "PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION" => PendingResponsibleIndividualVerification.some
      case "PENDING_GATEKEEPER_CHECK"                    => PendingGatekeeperCheck.some
      case "PENDING_SUBMITTER_VERIFICATION"              => PendingSubmitterVerification.some
      case "ACTIVE"                                      => Active.some
      case "DELETED"                                     => WasDeleted.some
      case "EXCLUDING_DELETED"                           => ExcludingDeleted.some
      case "BLOCKED"                                     => Blocked.some
      case "ANY"                                         => NoFiltering.some
      case _                                             => None
    }
  }
}
