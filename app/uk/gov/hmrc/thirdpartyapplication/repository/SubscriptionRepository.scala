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
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, UpdateOptions, Updates}
import org.mongodb.scala.model.Indexes.ascending

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json._
import play.api.libs.json.{JsObject, JsString, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor
import reactivemongo.bson.{BSONObjectID, BSONRegex}
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.{MongoComponent, ReactiveRepository}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.mongo.IndexHelper._

import scala.concurrent.{ExecutionContext, Future}
import reactivemongo.api.ReadConcern
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

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
    val builder = collection.BatchCommands.AggregationFramework

    val pipeline = List(
      builder.Match(Json.obj("apiIdentifier.version" -> version, "apiIdentifier.context" -> context)),
      builder.Project(Json.obj("applications" -> 1, "_id" -> 0)),
      builder.UnwindField("applications"),
      builder.Lookup(from = "application", localField = "applications", foreignField = "id", as = "applications"),
      builder.Project(Json.obj("collaborators" -> "$applications.collaborators.emailAddress")),
      builder.UnwindField("collaborators"),
      builder.UnwindField("collaborators"),
      builder.Group(JsString("$collaborators"))()
    )

    def partialEmailMatch(email: String) = {
      val caseInsensitiveRegExOption = "i"
      builder.Match(Json.obj("_id" -> BSONRegex(email, caseInsensitiveRegExOption)))
    }

    val pipelineWithOptionalEmailFilter =
      partialEmail match {
        case Some(email) => pipeline ++ List(partialEmailMatch(email))
        case None => pipeline
      }

    val query = collection.aggregateWith[JsObject]()(_ => (pipelineWithOptionalEmailFilter.head, pipelineWithOptionalEmailFilter.tail))

    val fList = query.collect(Int.MaxValue, Cursor.FailOnError[List[JsObject]]())
    fList.map {
      _.map {
        result => {
          val email = (result \ "_id").as[String]
          email
        }
      }
    }
  }

  def isSubscribed(applicationId: ApplicationId, apiIdentifier: ApiIdentifier) = {
    val selector = Some(Json.obj("$and" -> Json.arr(
        Json.obj("applications" -> applicationId.value.toString),
        Json.obj("apiIdentifier.context" -> apiIdentifier.context.value),
        Json.obj("apiIdentifier.version" -> apiIdentifier.version.value))
      ))

    collection.count(selector, None, 0, None, ReadConcern.Available)
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
    val builder = collection.BatchCommands.AggregationFramework
    val pipeline = List(
      builder.Lookup(from = "application", localField = "applications", foreignField = "id", as = "applications"),
      builder.Match(Json.obj("applications.collaborators.userId" -> userId.value)),
      builder.Project(Json.obj("context" -> "$apiIdentifier.context", "version" -> "$apiIdentifier.version", "_id" -> 0))
    )
    collection.aggregateWith[ApiIdentifier]()(_ => (pipeline.head, pipeline.tail)).collect(Int.MaxValue, Cursor.FailOnError[Set[ApiIdentifier]]())
  }
  
  @Deprecated
  def getSubscriptionsForDeveloper(email: String): Future[Set[ApiIdentifier]] = {
    val builder = collection.BatchCommands.AggregationFramework
    val pipeline = List(
      builder.Lookup(from = "application", localField = "applications", foreignField = "id", as = "applications"),
      builder.Match(Json.obj("applications.collaborators.emailAddress" -> email)),
      builder.Project(Json.obj("context" -> "$apiIdentifier.context", "version" -> "$apiIdentifier.version", "_id" -> 0))
    )
    collection.aggregateWith[ApiIdentifier]()(_ => (pipeline.head, pipeline.tail)).collect(Int.MaxValue, Cursor.FailOnError[Set[ApiIdentifier]]())
  }

  def getSubscribers(apiIdentifier: ApiIdentifier): Future[Set[ApplicationId]] = {
    collection.find(equal("apiIdentifier", Codecs.toBson(apiIdentifier)))
      .head()
      .map(_.applications)
  }

  private def queryFilter(apiIdentifier: ApiIdentifier): Bson = {
    and(
      equal("apiIdentifier.context", Codecs.toBson(apiIdentifier.context)),
      equal("apiIdentifier.version", Codecs.toBson(apiIdentifier.version))
    )
  }

  def add(applicationId: ApplicationId, apiIdentifier: ApiIdentifier): Future[HasSucceeded] = {
    collection.updateOne(
      filter = queryFilter(apiIdentifier),
      update = Updates.addToSet("applications", Codecs.toBson(applicationId)),
      options = new UpdateOptions().upsert(true)
    ).toFuture()
      .map(_ => HasSucceeded)
  }

  def remove(applicationId: ApplicationId, apiIdentifier: ApiIdentifier): Future[HasSucceeded] = {
    collection.updateOne(
      filter = queryFilter(apiIdentifier),
      update = Updates.pull("applications", Codecs.toBson(applicationId)),
      options = new UpdateOptions().upsert(true)
    ).toFuture()
     .map(_ => HasSucceeded)
  }
}
