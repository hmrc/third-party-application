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
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AnswerQuestion
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AskQuestion
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

@Singleton
class SubmissionsService @Inject()(
  questionnaireDAO: QuestionnaireDAO,
  submissionsDAO: SubmissionsDAO,
  applicationRepository: ApplicationRepository,
  subscriptionRepository: SubscriptionRepository
)(implicit val ec: ExecutionContext) extends EitherTHelper[String] {
  import cats.instances.future.catsStdInstancesForFuture

  /*
  * a questionnaire needs answering for the application
  */
  def create(applicationId: ApplicationId): Future[Either[String, Submission]] = {
    (
      for {
        groups                <- liftF(questionnaireDAO.fetchActiveGroupsOfQuestionnaires())
        allQuestionnaires     =  groups.flatMap(_.links)
        groupsOfIds           =  groups.map(_.toIds)
        allQuestionnaireIds   =  groupsOfIds.flatMap(_.links)
        submissionId          =  SubmissionId.random
        answers               =  AnswerQuestion.createMapFor(allQuestionnaires)
        submission            =  Submission(submissionId, applicationId, DateTimeUtils.now, groupsOfIds, answers)
        _                     <- liftF(submissionsDAO.save(submission))
      } yield submission
    )
    .value
  }

  def fetchLatest(id: ApplicationId): Future[Option[Submission]] = {
    submissionsDAO.fetchLatest(id)
  }
  
  def fetch(id: SubmissionId): Future[Option[Submission]] = {
    submissionsDAO.fetch(id)
  }

  def getNextQuestion(submissionId: SubmissionId, questionnaireId: QuestionnaireId): Future[Either[String, Option[Question]]] = {
    (
      for {
        submission          <- fromOptionF(submissionsDAO.fetch(submissionId), "No such submission")
        _                   =  cond(submission.hasQuestionnaire(questionnaireId), (), "Questionnaire not in this submission")
        questionnaire       <- fromOptionF(questionnaireDAO.fetch(questionnaireId), "No such questionnaire")
        application         <- fromOptionF(applicationRepository.fetch(submission.applicationId), "No such application")
        subscriptions       <- liftF(subscriptionRepository.getSubscriptions(submission.applicationId))
        context             =  DeriveContext.deriveFor(application, subscriptions)
        answers             =  submission.questionnaireAnswers(questionnaireId)
        question            =  AskQuestion.getNextQuestion(context)(questionnaire, answers)
      } yield question
    )
    .value
  }

  def recordAnswers(submissionId: SubmissionId, questionnaireId: QuestionnaireId, questionId: QuestionId, rawAnswers: NonEmptyList[String]): Future[Either[String, Submission]] = {
    (
      for {
        submission          <- fromOptionF(submissionsDAO.fetch(submissionId), "No such submission")
        _                   =  cond(submission.hasQuestionnaire(questionnaireId), (), "Questionnaire not in this submission")
        questionnaire       <- fromOptionF(questionnaireDAO.fetch(questionnaireId), "No such questionnaire")
        updatedSubmission   <- fromEither(AnswerQuestion.answer(submission, questionnaire, questionId, rawAnswers))
        _                   <- liftF(submissionsDAO.update(updatedSubmission))
      } yield updatedSubmission
    )
    .value
  }


  /*
  * When you delete an application
  */
  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Unit] = 
    submissionsDAO.deleteAllAnswersForApplication(applicationId)
}
