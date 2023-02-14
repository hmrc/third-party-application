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

package uk.gov.hmrc.apiplatform.modules.approvals.repositories

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList
import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.model.Filters.{and, equal, exists, lte}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions, Updates}

import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.ResponsibleIndividualVerificationState
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationId,
  ResponsibleIndividualVerificationState
}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationDeletedBase, ResponsibleIndividual, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

@Singleton
class ResponsibleIndividualVerificationRepository @Inject() (mongo: MongoComponent)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[ResponsibleIndividualVerification](
      collectionName = "responsibleIndividualVerification",
      mongoComponent = mongo,
      domainFormat = ResponsibleIndividualVerification.jsonFormatResponsibleIndividualVerification,
      extraCodecs = Codecs.playFormatSumCodecs(ResponsibleIndividualVerification.jsonFormatResponsibleIndividualVerification),
      indexes = Seq(
        IndexModel(
          ascending("id"),
          IndexOptions()
            .name("idIndex")
            .unique(true)
            .background(true)
        ),
        IndexModel(
          ascending("createdOn"),
          IndexOptions()
            .name("createdOnIndex")
            .background(true)
        ),
        IndexModel(
          ascending("applicationId", "submissionId", "submissionInstance"),
          IndexOptions()
            .name("appSubmissionIdIndex")
            .unique(true)
            .background(true)
        )
      ),
      replaceIndexes = true
    ) {

  def save(verification: ResponsibleIndividualVerification): Future[ResponsibleIndividualVerification] = {
    collection.insertOne(verification)
      .toFuture()
      .map(_ => verification)
  }

  def fetch(id: ResponsibleIndividualVerificationId): Future[Option[ResponsibleIndividualVerification]] = {
    collection.find(equal("id", Codecs.toBson(id)))
      .headOption()
  }

  def fetchByTypeStateAndAge(
      verificationType: String,
      state: ResponsibleIndividualVerificationState,
      minimumCreatedOn: LocalDateTime
    ): Future[List[ResponsibleIndividualVerification]] = {
    collection.find(and(
      equal("verificationType", verificationType),
      equal("state", Codecs.toBson(state)),
      lte("createdOn", minimumCreatedOn)
    )).toFuture()
      .map(_.toList)
  }

  def updateState(id: ResponsibleIndividualVerificationId, newState: ResponsibleIndividualVerificationState): Future[HasSucceeded] = {
    val filter = equal("id", Codecs.toBson(id))

    collection.updateOne(filter, update = Updates.set("state", Codecs.toBson(newState)))
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def delete(id: ResponsibleIndividualVerificationId): Future[HasSucceeded] = {
    collection.deleteOne(equal("id", Codecs.toBson(id)))
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def delete(submission: Submission): Future[HasSucceeded] = {
    deleteSubmissionInstance(submission.id, submission.latestInstance.index)
  }

  def deleteSubmissionInstance(id: Submission.Id, index: Int): Future[HasSucceeded] = {
    collection.deleteOne(
      and(
        equal("submissionId", Codecs.toBson(id)),
        equal("submissionInstance", Codecs.toBson(index))
      )
    )
      .toFuture()
      .map(_ => HasSucceeded)
  }

  private def deleteAllByApplicationId(id: ApplicationId): Future[HasSucceeded] = {
    collection.deleteMany(
      equal("applicationId", Codecs.toBson(id))
    )
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def findAll: Future[List[ResponsibleIndividualVerification]] = {
    collection.find()
      .toFuture()
      .map(x => x.toList)
  }

  def updateSetDefaultVerificationType(defaultTypeValue: String): Future[HasSucceeded] = {
    val filter = exists("verificationType", false)

    collection.updateMany(filter, update = Updates.set("verificationType", Codecs.toBson(defaultTypeValue)))
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def applyEvents(events: NonEmptyList[UpdateApplicationEvent]): Future[HasSucceeded] = {
    events match {
      case NonEmptyList(e, Nil)  => applyEvent(e)
      case NonEmptyList(e, tail) => applyEvent(e).flatMap(_ => applyEvents(NonEmptyList.fromListUnsafe(tail)))
    }
  }

  private def addResponsibleIndividualVerification(evt: ResponsibleIndividualVerificationStarted): Future[HasSucceeded] = {
    val verification = ResponsibleIndividualUpdateVerification(
      evt.verificationId,
      evt.applicationId,
      evt.submissionId,
      evt.submissionIndex,
      evt.applicationName,
      evt.eventDateTime,
      ResponsibleIndividual.build(evt.responsibleIndividualName, evt.responsibleIndividualEmail.text),
      evt.requestingAdminName,
      evt.requestingAdminEmail,
      ResponsibleIndividualVerificationState.INITIAL
    )

    deleteSubmissionInstance(evt.submissionId, evt.submissionIndex)
      .flatMap(_ => save(verification))
      .map(_ => HasSucceeded)
  }

  def deleteResponsibleIndividualVerification(code: String): Future[HasSucceeded] = {
    delete(ResponsibleIndividualVerificationId(code))
  }

  private def applyEvent(event: UpdateApplicationEvent): Future[HasSucceeded] = {
    event match {
      case evt: ResponsibleIndividualVerificationStarted           => addResponsibleIndividualVerification(evt)
      case evt: ResponsibleIndividualSet                           => deleteResponsibleIndividualVerification(evt.code)
      case evt: ResponsibleIndividualChanged                       => deleteResponsibleIndividualVerification(evt.code)
      case evt: ResponsibleIndividualDeclined                      => deleteSubmissionInstance(evt.submissionId, evt.submissionIndex)
      case evt: ResponsibleIndividualDeclinedUpdate                => deleteResponsibleIndividualVerification(evt.code)
      case evt: ResponsibleIndividualDidNotVerify                  => deleteSubmissionInstance(evt.submissionId, evt.submissionIndex)
      case evt: UpdateApplicationEvent with ApplicationDeletedBase => deleteAllByApplicationId(evt.applicationId)
      case _                                                       => Future.successful(HasSucceeded)
    }
  }
}
