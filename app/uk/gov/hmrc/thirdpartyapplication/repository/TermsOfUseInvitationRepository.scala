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

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import org.bson.BsonValue
import org.mongodb.scala.bson.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions, Updates}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.{TermsOfUseInvitationState, _}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{TermsOfUseInvitation, TermsOfUseInvitationWithApplication}
import uk.gov.hmrc.thirdpartyapplication.util.MetricsTimer

object TermsOfUseInvitationRepository {

  object MongoFormats extends MongoJavatimeFormats.Implicits {
    import play.api.libs.json.{Format, Json}
    implicit val formatInstant: Format[Instant]                           = MongoJavatimeFormats.instantFormat
    implicit val formatTermsOfUseInvitation: Format[TermsOfUseInvitation] = Json.format[TermsOfUseInvitation]
  }
}

@Singleton
class TermsOfUseInvitationRepository @Inject() (mongo: MongoComponent, val clock: Clock, val metrics: Metrics)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[TermsOfUseInvitation](
      collectionName = "termsOfUseInvitation",
      mongoComponent = mongo,
      domainFormat = TermsOfUseInvitationRepository.MongoFormats.formatTermsOfUseInvitation,
      indexes = Seq(
        IndexModel(
          ascending("applicationId"),
          IndexOptions()
            .name("applicationIdIndex")
            .unique(true)
            .background(true)
        ),
        IndexModel(
          ascending("status"),
          IndexOptions()
            .name("statusIndex")
            .background(true)
        ),
        IndexModel(
          ascending("dueBy"),
          IndexOptions()
            .name("dueByIndex")
            .background(true)
        )
      ),
      replaceIndexes = true
    ) with ApplicationLogger with MetricsTimer with ClockNow {

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

  def fetchByStatus(state: TermsOfUseInvitationState): Future[List[TermsOfUseInvitation]] = {
    collection.find(
      equal("status", Codecs.toBson(state))
    ).toFuture()
      .map(_.toList)
  }

  def fetchByStatusBeforeDueBy(state: TermsOfUseInvitationState, dueByBefore: Instant): Future[Seq[TermsOfUseInvitation]] = {
    collection.aggregate(
      Seq(
        filter(equal("status", Codecs.toBson(state))),
        filter(lte("dueBy", dueByBefore))
      )
    ).toFuture()
  }

  def fetchByStatusesBeforeDueBy(dueByBefore: Instant, states: TermsOfUseInvitationState*): Future[Seq[TermsOfUseInvitation]] = {
    val bsonStates = states.map(s => Codecs.toBson(s))
    collection.aggregate(
      Seq(
        filter(in("status", bsonStates: _*)),
        filter(lte("dueBy", dueByBefore))
      )
    ).toFuture()
  }

  def updateState(applicationId: ApplicationId, newState: TermsOfUseInvitationState): Future[HasSucceeded] = {
    val filter = equal("applicationId", Codecs.toBson(applicationId))
    val update = Updates.combine(
      Updates.set("status", Codecs.toBson(newState)),
      Updates.set("lastUpdated", instant())
    )
    collection.updateOne(filter, update)
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def updateReminderSent(applicationId: ApplicationId): Future[HasSucceeded] = {
    val filter = equal("applicationId", Codecs.toBson(applicationId))
    val update = Updates.combine(
      Updates.set("status", Codecs.toBson(REMINDER_EMAIL_SENT)),
      Updates.set("reminderSent", instant()),
      Updates.set("lastUpdated", instant())
    )
    collection.updateOne(filter, update)
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def updateResetBackToEmailSent(applicationId: ApplicationId, newDueBy: Instant): Future[HasSucceeded] = {
    val filter = equal("applicationId", Codecs.toBson(applicationId))
    val update = Updates.combine(
      Updates.set("status", Codecs.toBson(EMAIL_SENT)),
      Updates.set("dueBy", newDueBy),
      Updates.set("lastUpdated", instant())
    )
    collection.updateOne(filter, update)
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def delete(id: ApplicationId): Future[HasSucceeded] = {
    collection.deleteOne(
      equal("applicationId", Codecs.toBson(id))
    )
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def search(searchCriteria: TermsOfUseSearch): Future[Seq[TermsOfUseInvitationWithApplication]] = {
    val statusFilters = convertFilterToStatusQueryClause(searchCriteria.filters)
    val textFilter    = convertFilterToTextQueryClause(searchCriteria.filters, searchCriteria)
    runAggregationQuery(statusFilters, textFilter)
  }

  private def convertFilterToStatusQueryClause(filters: List[TermsOfUseSearchFilter]): Bson = {

    def statusMatch(states: TermsOfUseInvitationState*): Bson = {
      if (states.size == 0) {
        Document()
      } else {
        val bsonStates = states.map(s => Codecs.toBson(s))
        in("status", bsonStates: _*)
      }
    }

    def getFilterState(filter: TermsOfUseStatusFilter): TermsOfUseInvitationState = {
      filter match {
        case EmailSent                => EMAIL_SENT
        case ReminderEmailSent        => REMINDER_EMAIL_SENT
        case Overdue                  => OVERDUE
        case Warnings                 => WARNINGS
        case Failed                   => FAILED
        case TermsOfUseV2WithWarnings => TERMS_OF_USE_V2_WITH_WARNINGS
        case TermsOfUseV2             => TERMS_OF_USE_V2
      }
    }

    val statusFilters = filters.collect { case sf: TermsOfUseStatusFilter => sf }
    statusMatch(statusFilters.map(sf => getFilterState(sf)): _*)
  }

  private def convertFilterToTextQueryClause(filters: List[TermsOfUseSearchFilter], searchCriteria: TermsOfUseSearch): Bson = {

    def regexTextSearch(textFilters: List[TermsOfUseTextSearchFilter], searchText: String): Bson = {
      if (textFilters.size == 0) {
        Document()
      } else {
        regex("applications.name", searchText, "i")
      }
    }

    val textFilters = filters.collect { case sf: TermsOfUseTextSearchFilter => sf }
    regexTextSearch(textFilters, searchCriteria.textToSearch.getOrElse(""))
  }

  private def runAggregationQuery(statusFilters: Bson, textFilter: Bson) = {
    timeFuture("Run Terms Of Use Aggregation Query", "termsofuse.repository.runAggregationQuery") {

      collection.aggregate[BsonValue](
        Seq(
          filter(statusFilters),
          lookup(from = "application", localField = "applicationId", foreignField = "id", as = "applications"),
          filter(textFilter)
        )
      ).map(Codecs.fromBson[TermsOfUseInvitationWithApplication])
        .toFuture()

    }
  }
}
