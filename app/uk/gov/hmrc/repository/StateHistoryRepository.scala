/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.repository

import java.util.UUID
import javax.inject.{Inject, Singleton}

import play.api.libs.json.Json
import play.api.libs.json.Json._
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.models.JsonFormatters._
import uk.gov.hmrc.models.State.State
import uk.gov.hmrc.models.StateHistory.dateTimeOrdering
import uk.gov.hmrc.models.{HasSucceeded, StateHistory}
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.util.mongo.IndexHelper._

import scala.collection.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class StateHistoryRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[StateHistory, BSONObjectID]("stateHistory", mongo.mongoConnector.db, StateHistory.format, ReactiveMongoFormats.objectIdFormats) {

  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  override def indexes = Seq(
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
    collection.insert(stateHistory).map(_ => stateHistory)
  }

  def fetchByState(state: State): Future[Seq[StateHistory]] = {
    find("state" -> state)
  }

  def fetchByApplicationId(applicationId: UUID): Future[Seq[StateHistory]] = {
    find("applicationId" -> applicationId)
  }

  def fetchLatestByStateForApplication(applicationId: UUID, state: State): Future[Option[StateHistory]] = {
    find("applicationId" -> applicationId, "state" -> state).map(_.sortBy(_.changedAt).lastOption)
  }

  def deleteByApplicationId(applicationId: UUID): Future[HasSucceeded] = {
    collection.remove(Json.obj("applicationId" -> applicationId)).map(_ => HasSucceeded)
  }
}
