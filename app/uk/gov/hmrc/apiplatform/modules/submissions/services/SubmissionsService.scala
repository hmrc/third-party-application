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

package uk.gov.hmrc.apiplatform.modules.submissions.services

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services._
import uk.gov.hmrc.apiplatform.modules.submissions.repositories._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.time.DateTimeUtils
import cats.data.NonEmptyList
import org.joda.time.DateTime

@Singleton
class SubmissionsService @Inject()(
  questionnaireDAO: QuestionnaireDAO,
  submissionsDAO: SubmissionsDAO,
  contextService: ContextService
)(implicit val ec: ExecutionContext) extends EitherTHelper[String] {
  import cats.instances.future.catsStdInstancesForFuture

  private val emptyAnswers = Map.empty[QuestionId,ActualAnswer]

  def extendSubmission(submission: Submission): ExtendedSubmission = {
    val progress      =  AnswerQuestion.deriveProgressOfQuestionnaires(submission.allQuestionnaires, submission.context, submission.latestInstance.answersToQuestions)
    ExtendedSubmission(submission, progress)
  }
  
  def fetchAndExtend(submissionFn: => Future[Option[Submission]]): Future[Option[ExtendedSubmission]] = {
    (
      for {
        submission    <- fromOptionF(submissionFn, "ignored")
      }
      yield extendSubmission(submission)
    )
    .toOption
    .value 
  }  
  
  /*
  * a questionnaire needs answering for the application
  */
  def create(applicationId: ApplicationId, requestedBy: String): Future[Either[String, Submission]] = {
    (
      for {
        groups                <- liftF(questionnaireDAO.fetchActiveGroupsOfQuestionnaires())
        allQuestionnaires     =  groups.flatMap(_.links)
        submissionId          =  Submission.Id.random
        context               <- contextService.deriveContext(applicationId)
        newInstance           =  Submission.Instance(0, emptyAnswers, NonEmptyList.of(Submission.Status.Created(DateTime.now, requestedBy)))
        submission            =  Submission(submissionId, applicationId, DateTimeUtils.now, groups, QuestionnaireDAO.questionIdsOfInterest, NonEmptyList.of(newInstance), context)
        savedSubmission       <- liftF(submissionsDAO.save(submission))
      } yield savedSubmission
    )
    .value
  }

  def fetchLatest(applicationId: ApplicationId): Future[Option[Submission]] = {
    submissionsDAO.fetchLatest(applicationId)
  }
  
  def fetchLatestExtended(applicationId: ApplicationId): Future[Option[ExtendedSubmission]] = {
    fetchAndExtend(fetchLatest(applicationId))
  }

  def fetch(id: Submission.Id): Future[Option[ExtendedSubmission]] = {
    fetchAndExtend(submissionsDAO.fetch(id))
  }

  def fetchLatestMarkedSubmission(applicationId: ApplicationId): Future[Either[String, MarkedSubmission]] = {
    (
      for {
        submission    <- fromOptionF(fetchLatest(applicationId), "No such application submission")
        _             <- cond(submission.status.canBeMarked, (), "Submission cannot be marked yet")
        markedAnswers =  MarkAnswer.markSubmission(submission)
      } yield MarkedSubmission(submission, markedAnswers)
    )
    .value
  }

  def recordAnswers(submissionId: Submission.Id, questionId: QuestionId, rawAnswers: List[String]): Future[Either[String, ExtendedSubmission]] = {
    (
      for {
        initialSubmission       <- fromOptionF(submissionsDAO.fetch(submissionId), "No such submission")
        extSubmission           <- fromEither(AnswerQuestion.recordAnswer(initialSubmission, questionId, rawAnswers))
        savedSubmission         <- liftF(submissionsDAO.update(extSubmission.submission))
      } yield extSubmission.copy(submission = savedSubmission)
    )
    .value
  }

  /*
  * When you delete an application
  */
  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Unit] = 
    submissionsDAO.deleteAllAnswersForApplication(applicationId)

  def store(submission: Submission): Future[Submission] = 
    submissionsDAO.update(submission)
}
