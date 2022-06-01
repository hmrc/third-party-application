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

package uk.gov.hmrc.thirdpartyapplication.repository

import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import org.mongodb.scala.model.Indexes.{ascending, descending}

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.domain.models.StateHistory

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

@Singleton
class StateHistoryRepository @Inject()(mongo: MongoComponent)
                                      (implicit val ec: ExecutionContext)
  extends PlayMongoRepository[StateHistory](
    collectionName = "stateHistory",
    mongoComponent = mongo,
    domainFormat = StateHistory.format,
    indexes = Seq(IndexModel(ascending("applicationId"), IndexOptions()
      .name("applicationId")
      .background(true)
    ),
      IndexModel(ascending("state"), IndexOptions()
        .name("state")
        .background(true)
      ),
      IndexModel(ascending("applicationId", "state"), IndexOptions()
        .name("applicationId_state")
        .background(true)
      )
    ), replaceIndexes = true
  ) with MongoJavatimeFormats.Implicits {

  def insert(stateHistory: StateHistory): Future[StateHistory] = {
    collection.insertOne(stateHistory)
      .toFuture()
      .map(_ => stateHistory)
  }

  def fetchByState(state: State): Future[List[StateHistory]] = {
    collection.find(equal("state", Codecs.toBson(state)))
      .toFuture()
      .map(x => x.toList)
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

  def fetchLatestByStateForApplication(applicationId: ApplicationId, state: State): Future[Option[StateHistory]] = {
    collection.find(and(
      equal("applicationId", Codecs.toBson(applicationId)),
      equal("state", Codecs.toBson(state)))
    )
      .sort(descending("changedAt"))
      .headOption
  }

  def deleteByApplicationId(applicationId: ApplicationId): Future[HasSucceeded] = {
    collection.deleteOne(equal("applicationId", Codecs.toBson(applicationId)))
      .toFuture()
      .map(_ => HasSucceeded)
  }
}
