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

package uk.gov.hmrc.apiplatform.modules.submissions.services

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.NonEmptyList

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.{ClockNow, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services._
import uk.gov.hmrc.apiplatform.modules.submissions.repositories._

@Singleton
class SubmissionsService @Inject() (
    questionnaireDAO: QuestionnaireDAO,
    submissionsDAO: SubmissionsDAO,
    contextService: ContextService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends EitherTHelper[String] with ClockNow {
  import cats.instances.future.catsStdInstancesForFuture

  private val emptyAnswers = Map.empty[Question.Id, ActualAnswer]

  def extendSubmission(submission: Submission): ExtendedSubmission = {
    val progress = AnswerQuestion.deriveProgressOfQuestionnaires(submission.allQuestionnaires, submission.context, submission.latestInstance.answersToQuestions)
    ExtendedSubmission(submission, progress)
  }

  def fetchAndExtend(submissionFn: => Future[Option[Submission]]): Future[Option[ExtendedSubmission]] = {
    (
      for {
        submission <- fromOptionF(submissionFn, "ignored")
      } yield extendSubmission(submission)
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
        groups           <- liftF(questionnaireDAO.fetchActiveGroupsOfQuestionnaires())
        allQuestionnaires = groups.flatMap(_.links)
        submissionId      = SubmissionId.random
        context          <- contextService.deriveContext(applicationId)
        newInstance       = Submission.Instance(0, emptyAnswers, NonEmptyList.of(Submission.Status.Created(instant(), requestedBy)))
        submission        = Submission(submissionId, applicationId, instant(), groups, QuestionnaireDAO.questionIdsOfInterest, NonEmptyList.of(newInstance), context)
        savedSubmission  <- liftF(submissionsDAO.save(submission))
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

  def fetch(id: SubmissionId): Future[Option[ExtendedSubmission]] = {
    fetchAndExtend(submissionsDAO.fetch(id))
  }

  def fetchLatestMarkedSubmission(applicationId: ApplicationId): Future[Either[String, MarkedSubmission]] = {
    (
      for {
        submission   <- fromOptionF(fetchLatest(applicationId), "No such application submission")
        _            <- cond(submission.status.canBeMarked, (), "Submission cannot be marked yet")
        markedAnswers = MarkAnswer.markSubmission(submission)
      } yield MarkedSubmission(submission, markedAnswers)
    )
      .value
  }

  def recordAnswers(submissionId: SubmissionId, questionId: Question.Id, rawAnswers: List[String]): Future[Either[String, ExtendedSubmission]] = {
    (
      for {
        initialSubmission <- fromOptionF(submissionsDAO.fetch(submissionId), "No such submission")
        extSubmission     <- fromEither(AnswerQuestion.recordAnswer(initialSubmission, questionId, rawAnswers))
        savedSubmission   <- liftF(submissionsDAO.update(extSubmission.submission))
      } yield extSubmission.copy(submission = savedSubmission)
    )
      .value
  }

  /*
   * When you delete an application
   */
  def deleteAllAnswersForApplication(applicationId: ApplicationId): Future[Long] =
    submissionsDAO.deleteAllAnswersForApplication(applicationId)

  def store(submission: Submission): Future[Submission] =
    submissionsDAO.update(submission)

  def declineApplicationApprovalRequest(evt: ApplicationEvents.ApplicationApprovalRequestDeclined): Future[Option[Submission]] = {
    (
      for {
        extSubmission    <- fromOptionF(fetch(SubmissionId(evt.submissionId.value)), "submission not found")
        updatedSubmission = Submission.decline(evt.eventDateTime, evt.decliningUserEmail.text, evt.reasons)(extSubmission.submission) // Is this correct use of email addresss or should we use decliningUserName ?
        savedSubmission  <- liftF(store(updatedSubmission))
      } yield savedSubmission
    )
      .toOption
      .value
  }

  def declineSubmission(appId: ApplicationId, requestedByEmailAddress: String, reasons: String): Future[Option[Submission]] = {
    (
      for {
        submission       <- fromOptionF(fetchLatest(appId), "submission not found")
        updatedSubmission = Submission.decline(instant(), requestedByEmailAddress, reasons)(submission)
        savedSubmission  <- liftF(store(updatedSubmission))
      } yield savedSubmission
    )
      .toOption
      .value
  }

  def markSubmission(appId: ApplicationId, requestedByEmailAddress: String): Future[Option[Submission]] = {
    (
      for {
        submission       <- fromOptionF(fetchLatest(appId), "submission not found")
        updatedSubmission = Submission.automaticallyMark(instant(), requestedByEmailAddress)(submission)
        savedSubmission  <- liftF(store(updatedSubmission))
      } yield savedSubmission
    )
      .toOption
      .value
  }
}
