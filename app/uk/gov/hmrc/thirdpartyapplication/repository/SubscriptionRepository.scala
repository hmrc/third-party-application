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
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.bson.BsonValue
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Accumulators.sum
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters.{and, equal, not, size}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections.{computed, excludeId, fields, include}
import org.mongodb.scala.model.{IndexModel, IndexOptions, UpdateOptions, Updates}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.metrics.SubscriptionCountByApi
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.MetricsTimer
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import com.kenshoo.play.metrics.Metrics

@Singleton
class SubscriptionRepository @Inject() (mongo: MongoComponent, val metrics: Metrics)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[SubscriptionData](
      collectionName = "subscription",
      mongoComponent = mongo,
      domainFormat = SubscriptionData.format,
      indexes = Seq(
        IndexModel(
          ascending("apiIdentifier.context", "apiIdentifier.version"),
          IndexOptions()
            .name("context_version")
            .unique(true)
            .background(true)
        ),
        IndexModel(
          ascending("apiIdentifier.context"),
          IndexOptions()
            .name("context")
            .background(true)
        ),
        IndexModel(
          ascending("applications"),
          IndexOptions()
            .name("applications")
            .background(true)
        )
      ),
      replaceIndexes = true
    )
    with MetricsTimer
    with ApplicationLogger {

  def searchCollaborators(context: ApiContext, version: ApiVersion, partialEmail: Option[String]): Future[List[String]] = {
    timeFuture("Search Collaborators", "subscription.repository.searchCollaborators") {
      val pipeline = Seq(
        filter(
          Document(
            "apiIdentifier.context" -> Codecs.toBson(context),
            "apiIdentifier.version" -> Codecs.toBson(version)
          )
        ),
        project(fields(excludeId(), include("applications"))),
        unwind("$applications"),
        lookup(from = "application", localField = "applications", foreignField = "id", as = "applications"),
        project(fields(computed("collaborators", "$applications.collaborators.emailAddress"))),
        unwind("$collaborators"),
        unwind("$collaborators"),
        group("$collaborators")
      )

      def partialEmailMatch(email: String) = {
        filter(Document(s"""{_id: {$$regex: "$email", $$options: "i"} }"""))
      }

      val pipelineWithOptionalEmailFilter = {
        partialEmail match {
          case Some(email) => pipeline ++ Seq(partialEmailMatch(email))
          case None        => pipeline
        }
      }

      collection.aggregate[BsonValue](pipelineWithOptionalEmailFilter)
        .toFuture()
        .map(_.map(_.asDocument().get("_id").asString().getValue))
        .map(_.toList)
    }
  }

  def isSubscribed(applicationId: ApplicationId, apiIdentifier: ApiIdentifier): Future[Boolean] = {
    val filter = and(
      equal("applications", Codecs.toBson(applicationId)),
      equal("apiIdentifier.context", Codecs.toBson(apiIdentifier.context)),
      equal("apiIdentifier.version", Codecs.toBson(apiIdentifier.version))
    )

    collection.countDocuments(filter)
      .toFuture()
      .map(x => x > 0)
  }

  def getSubscriptions(applicationId: ApplicationId): Future[List[ApiIdentifier]] = {
    timeFuture("Subscriptions For an Application", "subscription.repository.getSubscriptions") {
      collection.find(equal("applications", Codecs.toBson(applicationId)))
        .toFuture()
        .map(_.map(_.apiIdentifier).toList)
    }
  }

  def getSubscriptionCountByApiCheckingApplicationExists: Future[List[SubscriptionCountByApi]] = {
    timeFuture("Subscription Count By Api", "subscription.repository.getSubscriptionCountByApiCheckingApplicationExists") {
      val pipeline = Seq(
        unwind("$applications"),
        lookup(from = "application", localField = "applications", foreignField = "id", as = "applicationDetail"),
        filter(not(size("applicationDetail", 0))),
        group("$apiIdentifier", sum("count", 1))
      )

      collection.aggregate[BsonValue](pipeline)
        .map(Codecs.fromBson[SubscriptionCountByApi])
        .toFuture()
        .map(_.toList)
    }
  }

  def getSubscribers(apiIdentifier: ApiIdentifier): Future[Set[ApplicationId]] = {
    timeFuture("Subscribers to an API", "subscription.repository.getSubscribers") {
      collection.find(contextAndVersionFilter(apiIdentifier))
        .headOption()
        .map {
          case Some(data) => data.applications
          case _          => Set.empty
        }
    }
  }

  def findAll: Future[List[SubscriptionData]] = {
    timeFuture("Find All Subscriptions", "subscription.repository.findAll") {
      collection.find()
        .toFuture()
        .map(x => x.toList)
    }
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
