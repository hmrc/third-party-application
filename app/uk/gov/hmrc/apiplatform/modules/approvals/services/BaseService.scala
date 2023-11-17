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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import java.time.{Clock, LocalDateTime}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actor
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository

abstract class BaseService(stateHistoryRepository: StateHistoryRepository, clock: Clock)(implicit ec: ExecutionContext) {

  def insertStateHistory(
      snapshotApp: StoredApplication,
      newState: State,
      oldState: Option[State],
      actor: Actor,
      rollback: StoredApplication => Any
    ): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, actor, oldState, changedAt = LocalDateTime.now(clock))
    stateHistoryRepository.insert(stateHistory)
      .andThen {
        case e: Failure[_] =>
          rollback(snapshotApp)
      }
  }
}
