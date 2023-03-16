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

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation

@Singleton
class TermsOfUseInvitationRepository @Inject() (mongo: MongoComponent)(implicit val ec: ExecutionContext) extends PlayMongoRepository[TermsOfUseInvitation](
      collectionName = "termsOfUseInvitation",
      mongoComponent = mongo,
      domainFormat = TermsOfUseInvitation.format,
      indexes = Seq(
        IndexModel(
          ascending("applicationId"),
          IndexOptions()
            .name("applicationIdIndex")
            .unique(true)
            .background(true)
        )
      ),
      replaceIndexes = true
    ) with ApplicationLogger {

  def create(termsOfUseInvitation: TermsOfUseInvitation): Future[Option[TermsOfUseInvitation]] = {
    collection.find(equal("applicationId", Codecs.toBson(termsOfUseInvitation.applicationId))).headOption().flatMap {
      case Some(value) => {
        logger.info(s"Cannot create terms of use invitation for application with id ${termsOfUseInvitation.applicationId.value} because an invitation already exists.")
        successful(None)
      }
      case None        => {
        collection.insertOne(termsOfUseInvitation).toFuture().map(_ => Some(termsOfUseInvitation))
      }
    }
  }

  def fetch(applicationId: ApplicationId): Future[Option[TermsOfUseInvitation]] = collection.find(equal("applicationId", Codecs.toBson(applicationId))).headOption()

  def fetchAll(): Future[List[TermsOfUseInvitation]] = collection.find().toFuture().map(seq => seq.toList)
}
