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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.repositories

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.services.UpliftLinkJsonFormatter
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpliftLinksRepository @Inject() (mongo: MongoComponent)
                                      (implicit val ec: ExecutionContext)
  extends PlayMongoRepository[UpliftLink](
      collectionName = "upliftlinks",
      mongoComponent = mongo,
      domainFormat = UpliftLinkJsonFormatter.jsonFormatUpliftLink,
      indexes = Seq(IndexModel(ascending("productionApplicationId"), IndexOptions()
            .name("productionApplicationIdIndex")
            .unique(true)
            .background(true)
        ),
        IndexModel(ascending("sandboxApplicationId"), IndexOptions()
            .name("sandboxApplicationIdIndex")
            .background(true)
        )
      )
    ) {

  def insert(upliftLink: UpliftLink): Future[UpliftLink] = {
    collection.insertOne(upliftLink)
      .toFuture()
      .map(_ => upliftLink)
  }

  def find(productionAppId: ApplicationId): Future[Option[ApplicationId]] = {
    collection.find(equal("productionApplicationId", Codecs.toBson(productionAppId)))
    .map(_.sandboxApplicationId)
    .headOption()
  }
}
