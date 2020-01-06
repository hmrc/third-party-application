/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.UUID

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json._
import play.api.libs.json.{JsObject, JsString, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor
import reactivemongo.bson.{BSONObjectID, BSONRegex}
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters.{formatApiIdentifier, formatSubscriptionData}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.mongo.IndexHelper._

import scala.collection.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class SubscriptionRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[SubscriptionData, BSONObjectID]("subscription", mongo.mongoConnector.db,
    MongoFormat.formatSubscriptionData, ReactiveMongoFormats.objectIdFormats) {

  def searchCollaborators(context: String, version: String, partialEmail: Option[String]): Future[Seq[String]] = {
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

    def partialEmailMatch(email :String) = {
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

  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  override def indexes = Seq(
    createSingleFieldAscendingIndex(
      indexFieldKey = "apiIdentifier.context",
      indexName = Some("context")
    ),
    createAscendingIndex(
      indexName = Some("context_version"),
      isUnique = true,
      isBackground = true,
      indexFieldsKey = List("apiIdentifier.context", "apiIdentifier.version"): _*
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "applications",
      indexName = Some("applications")
    )
  )

  def isSubscribed(applicationId: UUID, apiIdentifier: APIIdentifier) = {
    collection.count(Some(Json.obj("$and" -> Json.arr(
      Json.obj("applications" -> applicationId.toString),
      Json.obj("apiIdentifier.context" -> apiIdentifier.context),
      Json.obj("apiIdentifier.version" -> apiIdentifier.version)))))
    .map {
      case 1 => true
      case _ => false
    }
  }

  def getSubscriptions(applicationId: UUID): Future[Seq[APIIdentifier]] = {
    find("applications" -> applicationId.toString).map(_.map(_.apiIdentifier))
  }

  def getSubscribers(apiIdentifier: APIIdentifier): Future[Set[UUID]] = {
    val query = Json.obj("apiIdentifier" -> Json.toJson(apiIdentifier))
    collection.find(query, Option.empty[SubscriptionData]).one[SubscriptionData] map {
      case Some(subscriptionData) => subscriptionData.applications
      case _ => Set()
    }
  }

  private def makeSelector(apiIdentifier: APIIdentifier) = {
    Json.obj("$and" -> Json.arr(
      Json.obj("apiIdentifier.context" -> apiIdentifier.context),
      Json.obj("apiIdentifier.version" -> apiIdentifier.version)))
  }

  def add(applicationId: UUID, apiIdentifier: APIIdentifier) = {
    collection.update.one(
      makeSelector(apiIdentifier),
      Json.obj("$addToSet" -> Json.obj("applications" -> applicationId)),
      upsert = true
    ).map(_ => HasSucceeded)
  }

  def remove(applicationId: UUID, apiIdentifier: APIIdentifier) = {
    collection.update.one(
      makeSelector(apiIdentifier),
      Json.obj("$pull" -> Json.obj("applications" -> applicationId))
    ).map(_ => HasSucceeded)
  }
}
