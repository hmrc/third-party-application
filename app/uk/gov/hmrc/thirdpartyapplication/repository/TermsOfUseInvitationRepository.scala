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

import java.time.temporal.ChronoUnit.MILLIS
import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions, Updates}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.{TermsOfUseInvitationState, _}
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation

@Singleton
class TermsOfUseInvitationRepository @Inject() (mongo: MongoComponent, clock: Clock)(implicit val ec: ExecutionContext) extends PlayMongoRepository[TermsOfUseInvitation](
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
      Updates.set("lastUpdated", Instant.now(clock).truncatedTo(MILLIS))
    )
    collection.updateOne(filter, update)
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def updateReminderSent(applicationId: ApplicationId): Future[HasSucceeded] = {
    val filter = equal("applicationId", Codecs.toBson(applicationId))
    val update = Updates.combine(
      Updates.set("status", Codecs.toBson(REMINDER_EMAIL_SENT)),
      Updates.set("reminderSent", Instant.now(clock).truncatedTo(MILLIS)),
      Updates.set("lastUpdated", Instant.now(clock).truncatedTo(MILLIS))
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
      Updates.set("lastUpdated", Instant.now(clock).truncatedTo(MILLIS))
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
}
