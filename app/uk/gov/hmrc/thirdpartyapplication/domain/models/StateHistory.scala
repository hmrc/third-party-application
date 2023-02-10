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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import java.time.LocalDateTime

import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

case class StateHistory(applicationId: ApplicationId, state: State, actor: OldActor, previousState: Option[State] = None, notes: Option[String] = None, changedAt: LocalDateTime)

object StateHistory {
  import play.api.libs.json.Json

  implicit def dateTimeOrdering: Ordering[LocalDateTime] = Ordering.fromLessThan(_ isBefore _)

  implicit val ordering: Ordering[StateHistory] = Ordering.fromLessThan((a, b) => a.changedAt isBefore b.changedAt)

  implicit val dateFormat = MongoJavatimeFormats.localDateTimeFormat
  implicit val format     = Json.format[StateHistory]
}
