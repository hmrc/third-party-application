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

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Aggregates.{`match`, filter, group, lookup, project, unwind}
import org.mongodb.scala.model.Filters.{and, equal, regex}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections.{computed, excludeId, fields, include}
import org.mongodb.scala.model.{Aggregates, Filters, IndexModel, IndexOptions, UpdateOptions, Updates}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json._

@Singleton
class SubscriptionRepository @Inject()(mongo: MongoComponent)
                                      (implicit val ec: ExecutionContext)
  extends PlayMongoRepository[SubscriptionData](
    collectionName = "subscription",
    mongoComponent = mongo,
    domainFormat = SubscriptionData.format,
    indexes = Seq(
      IndexModel(ascending("apiIdentifier.context"), IndexOptions()
        .name("context")
        .background(true)
      ),
      IndexModel(ascending("applications"),IndexOptions()
        .name("applications")
        .background(true)
      ),
      IndexModel(ascending("apiIdentifier.context", "apiIdentifier.version"), IndexOptions()
        .name("context_version")
        .unique(true)
        .background(true)
      )
    )
  ) with MongoJavatimeFormats.Implicits {

  def searchCollaborators(context: ApiContext, version: ApiVersion, partialEmail: Option[String]): Future[List[String]] = {
    val pipelineStepOne = Seq(
      filter(and
        (equal("apiIdentifier.version", Codecs.toBson(version)),
         equal("apiIdentifier.context", Codecs.toBson(context)))
      ),
      project(fields(excludeId(), include("applications"))),
      unwind("applications")
    )

    val pipelineStepSecond = Seq(
      lookup(from ="application", localField ="applications", foreignField = "id", as = "applications"),
      project(fields(computed("collaborators", "$applications.collaborators.emailAddress"))),
      unwind("collaborators"),
      unwind("collaborators"),
      group("$collaborators")
    )

    val pipeline = pipelineStepOne ++ pipelineStepSecond

    def partialEmailMatch(email: String) = {
      val caseInsensitiveRegExOption = "i"
      `match`(equal("_id", regex(email, caseInsensitiveRegExOption)))
    }

    val pipelineWithOptionalEmailFilter =
      partialEmail match {
        case Some(email) => pipeline ++ Seq(partialEmailMatch(email))
        case None => pipeline
      }

    collection.aggregate(pipelineWithOptionalEmailFilter)
      .toFuture()
      .map {
        _.map {
          result => {
            val email = (result \ "_id").as[String]
          }
        }
      }
  }

  def isSubscribed(applicationId: ApplicationId, apiIdentifier: ApiIdentifier): Any = {
    val filter = and(
      equal("applications", Codecs.toBson(applicationId)),
      equal("apiIdentifier.context", Codecs.toBson(apiIdentifier.context)),
      equal("apiIdentifier.version", Codecs.toBson(apiIdentifier.version))
    )
   collection.countDocuments(filter)
     .toFuture()
     .map {
        case 1 => true
        case _ => false
     }
  }

  def getSubscriptions(applicationId: ApplicationId): Future[List[ApiIdentifier]] = {
    collection.find(equal("applications", Codecs.toBson(applicationId)))
      .toFuture()
      .map(_.map(_.apiIdentifier).toList)
  }

  def getSubscriptionsForDeveloper(userId: UserId): Future[Set[ApiIdentifier]] = {
    val pipeline = Seq(
      lookup(from ="application", localField ="applications", foreignField = "id", as = "applications"),
      filter(equal("applications.collaborators.userId", Codecs.toBson(userId))),
      project(
        fields(
          excludeId(),
          computed("context","$apiIdentifier.context"),
          computed("version","$apiIdentifier.version")
         )
       )
    )

    collection.aggregate(pipeline)
      .toFuture()
      .map {
        _.map(_.apiIdentifier)
         .toSet
      }
  }

  def getSubscribers(apiIdentifier: ApiIdentifier): Future[Set[ApplicationId]] = {
    collection.find(equal("apiIdentifier", Codecs.toBson(apiIdentifier)))
      .head()
      .map(_.applications)
  }

  private def contextAndVersionFilter(apiIdentifier: ApiIdentifier): Bson = {
    and(
      equal("apiIdentifier.context", Codecs.toBson(apiIdentifier.context)),
      equal("apiIdentifier.version", Codecs.toBson(apiIdentifier.version))
    )
  }

  def add(applicationId: ApplicationId, apiIdentifier: ApiIdentifier): Future[HasSucceeded] = {
    collection.updateOne(
      filter = contextAndVersionFilter(apiIdentifier),
      update = Updates.addToSet("applications", Codecs.toBson(applicationId)),
      options = new UpdateOptions().upsert(true)
    ).toFuture()
      .map(_ => HasSucceeded)
  }

  def remove(applicationId: ApplicationId, apiIdentifier: ApiIdentifier): Future[HasSucceeded] = {
    collection.updateOne(
      filter = contextAndVersionFilter(apiIdentifier),
      update = Updates.pull("applications", Codecs.toBson(applicationId)),
      options = new UpdateOptions().upsert(true)
    ).toFuture()
     .map(_ => HasSucceeded)
  }
}
