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

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.thirdpartyapplication.models.db.Notification
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import scala.concurrent.{ExecutionContext, Future}

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
}
