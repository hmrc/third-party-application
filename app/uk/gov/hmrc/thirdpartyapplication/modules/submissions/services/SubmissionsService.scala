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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.services

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.EitherTHelper
import uk.gov.hmrc.time.DateTimeUtils
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.NextQuestion
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.AnswerQuestion

@Singleton
class SubmissionsService @Inject()(
  questionnaireDAO: QuestionnaireDAO,
  submissionsDAO: SubmissionsDAO,
  contextService: ContextService
)(implicit val ec: ExecutionContext) extends EitherTHelper[String] {
  import cats.instances.future.catsStdInstancesForFuture

  /*
  * a questionnaire needs answering for the application
  */
  def create(applicationId: ApplicationId): Future[Either[String, ExtendedSubmission]] = {
    (
      for {
        groups                <- liftF(questionnaireDAO.fetchActiveGroupsOfQuestionnaires())
        allQuestionnaires     =  groups.flatMap(_.links)
        submissionId          =  SubmissionId.random
        answers               =  Map.empty[QuestionId,ActualAnswer]
        submission            =  Submission(submissionId, applicationId, DateTimeUtils.now, groups, answers)
        savedSubmission       <- liftF(submissionsDAO.save(submission))
        context               <- contextService.deriveContext(applicationId)
        nextQuestions         =  NextQuestion.deriveNextQuestions(savedSubmission, context)
        extendedSubmission    =  ExtendedSubmission(savedSubmission, nextQuestions)
      } yield extendedSubmission
    )
    .value
  }

  def fetchLatest(id: ApplicationId): Future[Option[ExtendedSubmission]] = {
    (
      for {
        submission            <- fromOptionF(submissionsDAO.fetchLatest(id), "No submission found for application")
        context               <- contextService.deriveContext(id)
        nextQuestions         =  NextQuestion.deriveNextQuestions(submission, context)
        extendedSubmission    =  ExtendedSubmission(submission, nextQuestions)
      } yield extendedSubmission
    )
    .toOption
    .value
  }
  
  def fetch(id: SubmissionId): Future[Option[ExtendedSubmission]] = {
     (
      for {
        submission            <- fromOptionF(submissionsDAO.fetch(id), "No such submission found")
        context               <- contextService.deriveContext(submission.applicationId)
        nextQuestions         =  NextQuestion.deriveNextQuestions(submission, context)
        extendedSubmission    =  ExtendedSubmission(submission, nextQuestions)
      } yield extendedSubmission
    )
    .toOption
    .value
  }

  def recordAnswers(submissionId: SubmissionId, questionId: QuestionId, rawAnswers: NonEmptyList[String]): Future[Either[String, ExtendedSubmission]] = {
    (
      for {
        submission          <- fromOptionF(submissionsDAO.fetch(submissionId), "No such submission")
        answeredSubmission  <- fromEither(AnswerQuestion.recordAnswer(submission, questionId, rawAnswers))
        savedSubmission     <- liftF(submissionsDAO.update(answeredSubmission))
        context             <- contextService.deriveContext(submission.applicationId)
        nextQuestions       =  NextQuestion.deriveNextQuestions(savedSubmission, context)
        extendedSubmission  =  ExtendedSubmission(savedSubmission, nextQuestions)
      } yield extendedSubmission
    )
    .value
  }

  /*
  * When you delete an application
  */
  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Unit] = 
    submissionsDAO.deleteAllAnswersForApplication(applicationId)
}
