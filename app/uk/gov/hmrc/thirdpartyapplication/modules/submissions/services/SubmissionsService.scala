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
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.AnswerQuestion
import cats.data.EitherT

@Singleton
class SubmissionsService @Inject()(
  questionnaireDAO: QuestionnaireDAO,
  submissionsDAO: SubmissionsDAO,
  contextService: ContextService
)(implicit val ec: ExecutionContext) extends EitherTHelper[String] {
  import cats.instances.future.catsStdInstancesForFuture

  private val emptyAnswers = Map.empty[QuestionId,ActualAnswer]

  def extendSubmission(submission: Submission): EitherT[Future, String, ExtendedSubmission] = {
    for {
      context       <- contextService.deriveContext(submission.applicationId)
      progress      =  AnswerQuestion.deriveProgressOfQuestionnaires(submission.allQuestionnaires, context, submission.answersToQuestions)
      extSubmission =  ExtendedSubmission(submission, progress)
    }
    yield extSubmission
  }
  
  def fetchAndExtend(submissionFn: => Future[Option[Submission]]): Future[Option[ExtendedSubmission]] = {
    (
      for {
        submission    <- fromOptionF(submissionFn, "ignored")
        extSubmission <- extendSubmission(submission)
      }
      yield extSubmission
    )
    .toOption
    .value 
  }  
  
  /*
  * a questionnaire needs answering for the application
  */
  def create(applicationId: ApplicationId): Future[Either[String, ExtendedSubmission]] = {
    (
      for {
        groups                <- liftF(questionnaireDAO.fetchActiveGroupsOfQuestionnaires())
        allQuestionnaires     =  groups.flatMap(_.links)
        submissionId          =  SubmissionId.random
        submission            =  Submission(submissionId, applicationId, DateTimeUtils.now, groups, emptyAnswers)
        savedSubmission       <- liftF(submissionsDAO.save(submission))
        extSubmission         <- extendSubmission(savedSubmission)
      } yield extSubmission
    )
    .value
  }

  def fetchLatest(applicationId: ApplicationId): Future[Option[ExtendedSubmission]] = {
    fetchAndExtend(submissionsDAO.fetchLatest(applicationId))
  }
  
  def fetch(id: SubmissionId): Future[Option[ExtendedSubmission]] = {
    fetchAndExtend(submissionsDAO.fetch(id))
  }

  def recordAnswers(submissionId: SubmissionId, questionId: QuestionId, rawAnswers: NonEmptyList[String]): Future[Either[String, ExtendedSubmission]] = {
    (
      for {
        initialSubmission   <- fromOptionF(submissionsDAO.fetch(submissionId), "No such submission")
        context             <- contextService.deriveContext(initialSubmission.applicationId)
        extSubmission       <- fromEither(AnswerQuestion.recordAnswer(initialSubmission, questionId, rawAnswers, context))
        savedSubmission     <- liftF(submissionsDAO.update(extSubmission.submission))
      } yield extSubmission
    )
    .value
  }

  /*
  * When you delete an application
  */
  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Unit] = 
    submissionsDAO.deleteAllAnswersForApplication(applicationId)
}
