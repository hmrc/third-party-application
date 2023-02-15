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

import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList
import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.Notification
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._

@Singleton
class NotificationRepository @Inject() (mongo: MongoComponent)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[Notification](
      collectionName = "notifications",
      mongoComponent = mongo,
      domainFormat = Notification.formatNotification,
      indexes = Seq(
        IndexModel(
          ascending("applicationId"),
          IndexOptions()
            .name("applicationIdIndex")
            .background(true)
        ),
        IndexModel(
          ascending("applicationId", "notificationType"),
          IndexOptions()
            .name("applicationId_notificationType")
            .unique(true)
            .background(true)
        )
      ),
      replaceIndexes = true
    ) {

  def createEntity(notification: Notification): Future[Boolean] =
    collection.insertOne(notification).toFuture().map(wr => wr.wasAcknowledged())

  def deleteAllByApplicationId(applicationId: ApplicationId): Future[HasSucceeded] = {
    collection.deleteMany(equal("applicationId", Codecs.toBson(applicationId)))
      .toFuture()
      .map(_ => HasSucceeded)
  }

  // TODO - remove this method and extract to command handlers
  def applyEvents(events: NonEmptyList[AbstractApplicationEvent]): Future[HasSucceeded] = {
    events match {
      case NonEmptyList(e, Nil)  => applyEvent(e)
      case NonEmptyList(e, tail) => applyEvent(e).flatMap(_ => applyEvents(NonEmptyList.fromListUnsafe(tail)))
    }
  }

  private def applyEvent(event: AbstractApplicationEvent): Future[HasSucceeded] = {
    event match {
      case _ : ApplicationDeleted                       => deleteAllByApplicationId(event.applicationId)
      case _ : ApplicationDeletedByGatekeeper           => deleteAllByApplicationId(event.applicationId)
      case _ : ProductionCredentialsApplicationDeleted  => deleteAllByApplicationId(event.applicationId)
      case _                                            => Future.successful(HasSucceeded)
    }
  }
}
