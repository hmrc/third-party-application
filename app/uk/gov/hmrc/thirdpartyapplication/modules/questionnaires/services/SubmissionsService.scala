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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.services

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.EitherTHelper
import uk.gov.hmrc.time.DateTimeUtils
import scala.collection.immutable.ListMap

@Singleton
class SubmissionsService @Inject()(
  questionnaireDAO: QuestionnaireDAO,
  submissionsDAO: SubmissionsDAO
)(implicit val ec: ExecutionContext) extends EitherTHelper[String] {
  
  import cats.instances.future.catsStdInstancesForFuture

  /*
  * a questionnaire needs answering for the application
  */
  def create(applicationId: ApplicationId): Future[Either[String, Submission]] = {
    (
      for {
        groups <- liftF(questionnaireDAO.fetchActiveGroupsOfQuestionnaires())
        questionnaireIds = groups.flatMap(_.links)
        submissionId = SubmissionId.random
        answers: List[AnswersToQuestionnaire] = questionnaireIds.map(qid => AnswersToQuestionnaire(qid, ListMap.empty))
        submission = Submission(submissionId, applicationId, DateTimeUtils.now, groups, answers)
        _ <- liftF(submissionsDAO.save(submission))
      } yield submission
    )
    .value
  }

  def fetchLatest(id: ApplicationId): Future[Option[Submission]] = {
    submissionsDAO.fetchLatest(id)
  }
  
  // def createAnswersToQuestionnaire(submissionId: SubmissionId, questionnaire: Questionnaire) = {

  // }

  // def fetchLatest(applicationId: ApplicationId): Future[Either[String, Map[QuestionnaireId, ReferenceId]]] = {
  //   ???
  // }

  // def recordAnswer(referenceId: ReferenceId, questionId: QuestionId, rawAnswers: NonEmptyList[String]): Future[Either[String, AnswersToQuestionnaire]] = {
  //   ???

    // (
    //   for {
    //     answersToQ    <- fromOptionF(answersDAO.fetch(referenceId), "No such referenceId")
    //     questionnaire <- fromOptionF(questionnaireDAO.fetch(answersToQ.questionnaireId), "No such questionnaire")
    //     questionItem  <- fromOption(questionnaire.questions.find(_.question.id == questionId), "No such question")
    //     answer        <- fromEither(AskQuestion.validateAnswersToQuestion(questionItem.question, rawAnswers))
    //     newAtQ         = answersToQ.copy(answers = answersToQ.answers + (questionId -> answer))
    //     _             <- liftF(answersDAO.save(newAtQ))
    //   } yield newAtQ
    // )
    // .value
  // }

  /*
  * When you delete an application
  */
  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Unit] = ???  // TODO
}
