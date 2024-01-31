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

import java.time.Instant

import play.api.libs.json.{Json, OFormat}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}

case class StateHistoryResponse(applicationId: ApplicationId, state: State, actor: Actor, notes: Option[String], changedAt: Instant)

object StateHistoryResponse {
  def from(sh: StateHistory) = StateHistoryResponse(sh.applicationId, sh.state, sh.actor, sh.notes, sh.changedAt)

  import uk.gov.hmrc.thirdpartyapplication.domain.utils.UtcMillisDateTimeFormatters._

  implicit val format: OFormat[StateHistoryResponse] = Json.format[StateHistoryResponse]
}
