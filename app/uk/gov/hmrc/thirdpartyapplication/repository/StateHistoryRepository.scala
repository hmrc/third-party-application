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

package uk.gov.hmrc.thirdpartyapplication.repository

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.thirdpartyapplication.models.{HasSucceeded, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.State.State
import uk.gov.hmrc.thirdpartyapplication.models.StateHistory.dateTimeOrdering
import uk.gov.hmrc.thirdpartyapplication.util.mongo.IndexHelper._

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

@Singleton
class StateHistoryRepository @Inject()(mongo: ReactiveMongoComponent)(implicit val ec: ExecutionContext)
  extends ReactiveRepository[StateHistory, BSONObjectID]("stateHistory", mongo.mongoConnector.db, StateHistory.format, ReactiveMongoFormats.objectIdFormats) {

  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  override def indexes = List(
    createSingleFieldAscendingIndex(
      indexFieldKey = "applicationId",
      indexName = Some("applicationId")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "state",
      indexName = Some("state")
    ),
    createAscendingIndex(
      indexName = Some("applicationId_state"),
      isUnique = false,
      isBackground = true,
      indexFieldsKey = List("applicationId", "state"): _*
    )
  )

  def insert(stateHistory: StateHistory): Future[StateHistory] = {
    collection.insert.one(stateHistory).map(_ => stateHistory)
  }

  def fetchByState(state: State): Future[List[StateHistory]] = {
    find("state" -> state)
  }

  def fetchByApplicationId(applicationId: ApplicationId): Future[List[StateHistory]] = {
    find("applicationId" -> applicationId.value)
  }

  def fetchLatestByStateForApplication(applicationId: ApplicationId, state: State): Future[Option[StateHistory]] = {
    find("applicationId" -> applicationId.value, "state" -> state).map(_.sortBy(_.changedAt).lastOption)
  }

  def deleteByApplicationId(applicationId: ApplicationId): Future[HasSucceeded] = {
    remove("applicationId" -> applicationId.value).map(_ => HasSucceeded)
  }
}
