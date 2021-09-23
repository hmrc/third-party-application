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
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.time.DateTimeUtils
import javax.inject.{Inject, Singleton}
import scala.collection.immutable.ListMap
import play.api.libs.json.Json

@Singleton
class AnswersToQuestionnaireDAO @Inject()(repo: AnswersRepository)(implicit ec: ExecutionContext) {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AnswersToQuestionnaireJsonFormatters._

  private def byReferenceId(referenceId: ReferenceId): (String, Json.JsValueWrapper) = ("referenceId", referenceId.value)

  def fetch(id: ReferenceId): Future[Option[AnswersToQuestionnaire]] = 
    repo
    .find(query = byReferenceId(id))
    .map(_.headOption)

  def save(answers: AnswersToQuestionnaire): Future[AnswersToQuestionnaire] = {
    val updateObj = Json.toJson(answers)

    repo.findAndUpdate(
      Json.obj("referenceId" -> answers.referenceId),
      Json.obj("$set" -> updateObj),
      fetchNewObject = true,
      upsert = true)
      .map(_.result[AnswersToQuestionnaire])
      .map {
        _.getOrElse(throw new RuntimeException(s"Failed to save ${answers.referenceId}"))
      }
  }

  def create(applicationId: ApplicationId, questionnaireId: QuestionnaireId): Future[ReferenceId] = {
    val referenceId = ReferenceId.random

    for {
      saved <- save(AnswersToQuestionnaire(referenceId, questionnaireId, applicationId, DateTimeUtils.now, ListMap.empty))
    } yield referenceId
  }
}
