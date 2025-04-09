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

package uk.gov.hmrc.thirdpartyapplication.repository

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.model.Filters.{and, equal, in}
import org.mongodb.scala.model.Indexes.{ascending, descending}
import org.mongodb.scala.model.{IndexModel, IndexOptions}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

object StateHistoryRepository {

  object MongoFormats {
    import play.api.libs.json.{Format, Json, OFormat}
    implicit val formatInstant: Format[Instant]            = MongoJavatimeFormats.instantFormat
    implicit val formatStateHistory: OFormat[StateHistory] = Json.format[StateHistory]
  }
}

@Singleton
class StateHistoryRepository @Inject() (mongo: MongoComponent)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[StateHistory](
      collectionName = "stateHistory",
      mongoComponent = mongo,
      domainFormat = StateHistoryRepository.MongoFormats.formatStateHistory,
      indexes = Seq(
        IndexModel(
          ascending("applicationId"),
          IndexOptions()
            .name("applicationId")
            .background(true)
        ),
        IndexModel(
          ascending("state"),
          IndexOptions()
            .name("state")
            .background(true)
        ),
        IndexModel(
          ascending("applicationId", "state"),
          IndexOptions()
            .name("applicationId_state")
            .background(true)
        )
      ),
      replaceIndexes = true
    ) {

  def insert(stateHistory: StateHistory): Future[StateHistory] = {
    collection.insertOne(stateHistory)
      .toFuture()
      .map(_ => stateHistory)
  }

  def fetchByState(state: State): Future[List[StateHistory]] = {
    collection.find(equal("state", Codecs.toBson(state)))
      .toFuture()
      .map(_.toList)
  }

  def findAll: Future[List[StateHistory]] = {
    collection.find()
      .toFuture()
      .map(x => x.toList)
  }

  def fetchByApplicationId(applicationId: ApplicationId): Future[List[StateHistory]] = {
    collection.find(equal("applicationId", Codecs.toBson(applicationId)))
      .toFuture()
      .map(x => x.toList)
  }

  def fetchDeletedByApplicationIds(applicationIds: List[ApplicationId]): Future[List[StateHistory]] = {
    collection.find(and(
      in("applicationId", applicationIds.map(i => Codecs.toBson(i)): _*),
      equal("state", Codecs.toBson(State.DELETED.toString))
    ))
      .toFuture()
      .map(x => x.toList)
  }

  def fetchLatestByStateForApplication(applicationId: ApplicationId, state: State): Future[Option[StateHistory]] = {
    collection.find(and(
      equal("applicationId", Codecs.toBson(applicationId)),
      equal("state", Codecs.toBson(state))
    ))
      .sort(descending("changedAt"))
      .headOption()
  }

  def deleteByApplicationId(applicationId: ApplicationId): Future[HasSucceeded] = {
    collection.deleteMany(equal("applicationId", Codecs.toBson(applicationId)))
      .toFuture()
      .map(_ => HasSucceeded)
  }
}
