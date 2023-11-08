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

package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Sorts.descending

import uk.gov.hmrc.mongo.play.json.Codecs

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId

@Singleton
class SubmissionsDAO @Inject() (submissionsRepository: SubmissionsRepository)(implicit val ec: ExecutionContext) {

  private lazy val collection = submissionsRepository.collection

  def save(submission: Submission): Future[Submission] = {
    collection.insertOne(submission)
      .toFuture()
      .map(_ => submission)
  }

  def update(submission: Submission): Future[Submission] = {
    val query = equal("id", Codecs.toBson(submission.id))

    collection.find(query).headOption().flatMap {
      case Some(_: Submission) =>
        collection.replaceOne(
          filter = query,
          replacement = submission
        ).toFuture().map(_ => submission)

      case None => collection.insertOne(submission).toFuture().map(_ => submission)
    }
  }

  def fetchLatest(id: ApplicationId): Future[Option[Submission]] = {
    collection
      .withReadPreference(com.mongodb.ReadPreference.primary())
      .find(equal("applicationId", Codecs.toBson(id)))
      .sort(descending("startedOn"))
      .headOption()
  }

  def fetch(id: SubmissionId): Future[Option[Submission]] = {
    collection.find(equal("id", Codecs.toBson(id)))
      .headOption()
  }

  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Long] = {
    collection.deleteOne(equal("applicationId", Codecs.toBson(applicationId)))
      .toFuture()
      .map(x => x.getDeletedCount)
  }
}
