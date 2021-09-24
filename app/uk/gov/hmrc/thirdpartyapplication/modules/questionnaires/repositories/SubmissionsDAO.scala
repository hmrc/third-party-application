/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories

import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import play.api.libs.json.JsObject
import reactivemongo.api.ReadPreference
import reactivemongo.api.Cursor

@Singleton
class SubmissionsDAO @Inject()(repo: SubmissionsRepository)(implicit ec: ExecutionContext) {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.SubmissionsJsonFormatters._
  
  private val DESCENDING = -1

  // private def bySubmissionId(id: SubmissionId): (String, Json.JsValueWrapper) = ("submissionId", id.value)

  def save(submission: Submission): Future[Submission] = {
    repo.insert(submission)
    .map(_ => submission)
  }

  def fetchLatest(id: ApplicationId): Future[Option[Submission]] = {
    repo
    .collection
    .find[JsObject](selector = Json.obj("applicationId" -> id))
    .sort(Json.obj("startedOn" -> DESCENDING))
    .cursor[Submission](ReadPreference.primary)
    .collect[List](1, Cursor.FailOnError[List[Submission]]())
    .map(_.headOption)
  }

  // def create(applicationId: ApplicationId, questionnaireId: QuestionnaireId): Future[ReferenceId] = {
  //   val submissionId = SubmissionId.random

  //   for {
  //     saved <- save(AnswersToQuestionnaire(referenceId, questionnaireId, applicationId, DateTimeUtils.now, ListMap.empty))
  //   } yield referenceId
  // }
}
