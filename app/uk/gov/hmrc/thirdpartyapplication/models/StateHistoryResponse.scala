/*
 * Copyright 2022 HM Revenue & Customs
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

import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.domain.models._

import java.time.LocalDateTime

case class StateHistoryResponse(applicationId: ApplicationId, state: State, actor: OldActor, notes: Option[String], changedAt: LocalDateTime)

object StateHistoryResponse {
  def from(sh: StateHistory) = StateHistoryResponse(sh.applicationId, sh.state, sh.actor, sh.notes, sh.changedAt)

  import uk.gov.hmrc.thirdpartyapplication.domain.utils.UtcMillisDateTimeFormatters._
  implicit val format = Json.format[StateHistoryResponse]
}
