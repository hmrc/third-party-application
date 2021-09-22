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
import cats.implicits._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.time.DateTimeUtils
import javax.inject.{Inject, Singleton}
import scala.collection.immutable.ListMap
import play.api.libs.json.Json

@Singleton
class AnswersToQuestionnaireDAO @Inject()(repo: AnswersRepository)(implicit ec: ExecutionContext) {

  def fetch(id: ReferenceId): Future[Option[AnswersToQuestionnaire]] = 
    repo
    .find(query = ("referenceId", Json.toJson(id.value)))
    .map(_.headOption)

  def findAll(applicationId: ApplicationId, questionnaireId: QuestionnaireId): Future[List[AnswersToQuestionnaire]] = {
    //   store
    //   .filter {
    //     case (_, answers) => answers.applicationId == applicationId && answers.questionnaireId == questionnaireId
    //   }
    //   .map(_._2)
    //   .toList
    //   .pure[Future]
    ???
  }

  def findLatest(applicationId: ApplicationId, questionnaireId: QuestionnaireId): Future[Option[AnswersToQuestionnaire]] = {
  //   findAll(applicationId, questionnaireId)
  //   .map {
  //     _.sortBy(a => a.startedOn.getMillis)
  //   }
  //   .map(_.reverse)
  //   .map(_.headOption)
  ???
  }

  def save(answers: AnswersToQuestionnaire): Future[AnswersToQuestionnaire] = {
  //   store.put(answers.referenceId, answers)
  //   answers.pure[Future]
  ???
  }

  def create(applicationId: ApplicationId, questionnaireId: QuestionnaireId): Future[ReferenceId] = {
  //   val referenceId = ReferenceId.random

  //   for {
  //     saved <- save(AnswersToQuestionnaire(referenceId, questionnaireId, applicationId, DateTimeUtils.now, ListMap.empty))
  //   } yield referenceId
  ???
  }
}
