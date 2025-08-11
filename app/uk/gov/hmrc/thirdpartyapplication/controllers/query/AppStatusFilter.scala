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

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._

sealed trait AppStateFilter

object AppStateFilter {
  case class Matching(state: State) extends AppStateFilter

  case object Active                                   extends AppStateFilter
  case object ExcludingDeleted                         extends AppStateFilter

  case object Blocked                                  extends AppStateFilter

  case object NoFiltering                              extends AppStateFilter


  def apply(text: String): Option[AppStateFilter] = {
    import cats.implicits._
    text match {
      case "CREATED"                                     => Matching(State.TESTING).some
      case "PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION" => Matching(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION).some
      case "PENDING_GATEKEEPER_CHECK"                    => Matching(State.PENDING_GATEKEEPER_APPROVAL).some
      case "PENDING_SUBMITTER_VERIFICATION"              => Matching(State.PENDING_REQUESTER_VERIFICATION).some

      case "ACTIVE"                                      => Active.some
      case "EXCLUDING_DELETED"                           => ExcludingDeleted.some
      case "BLOCKED"                                     => Blocked.some
      case "ANY"                                         => NoFiltering.some
      case text                                          => State(text).map(Matching)
    }
  }
}
