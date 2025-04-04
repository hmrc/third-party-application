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

package uk.gov.hmrc.apiplatform.modules.test_only.repository

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import org.bson.conversions.Bson
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model._

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.test_only.repository.TestApplicationsRepository.TestApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.MetricsTimer

object TestApplicationsRepository {
  case class TestApplicationData(id: ApplicationId, createdOn: Instant = Instant.now)

  implicit val formatInstant: Format[Instant] = MongoJavatimeFormats.instantFormat

  implicit val format: Format[TestApplicationData] = Json.format[TestApplicationData]
}

@Singleton
class TestApplicationsRepository @Inject() (mongo: MongoComponent, val metrics: Metrics)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[TestApplicationsRepository.TestApplicationData](
      collectionName = "testApplications",
      mongoComponent = mongo,
      domainFormat = TestApplicationsRepository.format,
      indexes = Seq(
        IndexModel(
          ascending("id"),
          IndexOptions()
            .name("applicationIdIndex")
            .unique(true)
            .background(true)
        )
      ),
      replaceIndexes = true
    )
    with MetricsTimer
    with ApplicationLogger {

  def record(appId: ApplicationId): Future[Boolean] = {
    collection.insertOne(TestApplicationData(appId, Instant.now())).toFuture().map(_.wasAcknowledged())
  }

  def delete(appId: ApplicationId): Future[Boolean] = {
    logger.info("Deleting test application marker")
    val query = equal("id", Codecs.toBson(appId))
    collection.deleteOne(query).toFuture().map(_.wasAcknowledged())
  }

  def findCreatedBefore(pointInTime: Instant): Future[List[ApplicationId]] = {
    val query: Bson = lt("createdOn", pointInTime)
    collection.find(query).toFuture().map(_.toList.map(_.id))
  }
}
