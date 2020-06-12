/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.UUID

import uk.gov.hmrc.thirdpartyapplication.models.StateHistory.dateTimeOrdering
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.time.DateTimeUtils

class StateHistorySpec extends HmrcSpec {

  val applicationId = UUID.randomUUID()
  val now = DateTimeUtils.now
  val actor = Actor("admin@example.com", ActorType.COLLABORATOR)

  "State history" should {
    "sort by date" in {
      val stateHistory1 = StateHistory(applicationId, State.TESTING, actor, changedAt = now.minusHours(5))
      val stateHistory2 = StateHistory(applicationId, State.TESTING, actor, changedAt = now.minusHours(3))
     
      Seq(stateHistory2, stateHistory1).sortBy(_.changedAt) should contain inOrder(stateHistory1, stateHistory2)
    }
  }

}
