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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.service

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.EitherTHelper

@Singleton
class QuestionnaireService @Inject()(
  questionnaireDAO: QuestionnaireDAO,
  answersDAO: AnswersToQuestionnaireDAO
)(implicit val ec: ExecutionContext) extends EitherTHelper[String] {
  
  type QuestionnaireAndAnswers = String // TODO

  import cats.instances.future.catsStdInstancesForFuture

  /*
  * a questionnaire needs answering for the application
  */
  def raiseQuestionnaire(applicationId: ApplicationId, questionnaireId: QuestionnaireId): Future[Either[String, ReferenceId]] = {
    (
      for {
        questionnaire <- fromOptionF(questionnaireDAO.fetch(questionnaireId), "No such questionnaire")
        referenceId <- liftF(answersDAO.create(applicationId, questionnaireId))
      } yield referenceId
    )
    .value
  }

  def fetch(referenceId: ReferenceId): Future[Either[String, (Questionnaire, AnswersToQuestionnaire)]] = {
    (
      for {
        answers <- fromOptionF(answersDAO.fetch(referenceId), "No such referenceId")
        questionnaire <- fromOptionF(questionnaireDAO.fetch(answers.questionnaireId), "No such questionnaire")
      } yield ((questionnaire, answers))
    )
    .value
  }

  /*
  * When you delete an application
  */
  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Unit] = ???
}