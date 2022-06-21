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

package uk.gov.hmrc.apiplatform.modules.approvals.repositories

import akka.stream.Materializer
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.ResponsibleIndividualVerificationState
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.repository.MongoJavaTimeFormats

import java.time.LocalDateTime
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.thirdpartyapplication.util.mongo.IndexHelper._

class ResponsibleIndividualVerificationRepository @Inject() (mongo: ReactiveMongoComponent)(implicit val mat: Materializer, val ec: ExecutionContext)
    extends ReactiveRepository[ResponsibleIndividualVerification, BSONObjectID](
      "responsibleIndividualVerification",
      mongo.mongoConnector.db,
      ResponsibleIndividualVerification.format,
      ReactiveMongoFormats.objectIdFormats
    ) {

  override def indexes = List(
    createSingleFieldAscendingIndex(
      indexFieldKey = "id",
      isUnique = true,
      indexName = Some("responsibleIndividualVerificationIdIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "createdOn",
      indexName = Some("responsibleIndividualVerificationCreatedOnIndex")
    ),
    createAscendingIndex(
      indexName = Some("responsibleIndividualVerificationAppSubmissionIdIndex"),
      isUnique = true,
      isBackground = true,
      "applicationId",
      "submissionId",
      "submissionInstance"
    )
  )

  def save(verification: ResponsibleIndividualVerification): Future[ResponsibleIndividualVerification] = {
    insert(verification).map(_ => verification)
  }

  def fetch(id: ResponsibleIndividualVerificationId): Future[Option[ResponsibleIndividualVerification]] = {
    find("id" -> id.value).map(_.headOption)
  }

  def fetchByStateAndAge(state: ResponsibleIndividualVerificationState, minimumCreatedOn: LocalDateTime): Future[List[ResponsibleIndividualVerification]] = {
    implicit val dateFormat = MongoJavaTimeFormats.localDateTimeFormat
    find("state" -> state, "createdOn" -> Json.obj("$lte" -> minimumCreatedOn))
  }

  def updateState(id: ResponsibleIndividualVerificationId, newState: ResponsibleIndividualVerificationState): Future[HasSucceeded] = {
    collection.update.one(Json.obj("id" -> id), Json.obj("$set" -> Json.obj("state" -> newState))).map(_ => HasSucceeded)
  }

  def delete(id: ResponsibleIndividualVerificationId): Future[HasSucceeded] = {
    collection.delete.one(Json.obj("id" -> id)).map(_ => HasSucceeded)
  }

  def delete(submission: Submission): Future[HasSucceeded] = {
    collection.delete.one(Json.obj("submissionId" -> submission.id, "submissionInstance" -> submission.latestInstance.index)).map(_ => HasSucceeded)
  }
}
