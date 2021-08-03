/*
 * Copyright 2021 HM Revenue & Customs
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

import State.State
import org.joda.time.DateTime
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import play.api.libs.json.Json

case class StateHistory(applicationId: ApplicationId,
                        state: State,
                        actor: Actor,
                        previousState: Option[State] = None,
                        notes: Option[String] = None,
                        changedAt: DateTime = DateTimeUtils.now)

object StateHistory {
  implicit def dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)

  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val format = Json.format[StateHistory]
}
