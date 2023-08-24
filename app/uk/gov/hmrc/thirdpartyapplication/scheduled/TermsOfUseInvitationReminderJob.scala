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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit._

import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import cats.implicits._
import com.google.inject.Singleton

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, TermsOfUseInvitation}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, TermsOfUseInvitationRepository}

@Singleton
class TermsOfUseInvitationReminderJob @Inject() (
    termsOfUseInvitationReminderLockService: TermsOfUseInvitationReminderJobLockService,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    applicationRepository: ApplicationRepository,
    submissionService: SubmissionsService,
    emailConnector: EmailConnector,
    clock: Clock,
    jobConfig: TermsOfUseInvitationReminderJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  val termsOfUseInvitationReminderInterval: FiniteDuration = jobConfig.reminderInterval
  override def name: String                                = "TermsOfUseInvitationReminderJob"
  override def interval: FiniteDuration                    = jobConfig.interval
  override def initialDelay: FiniteDuration                = jobConfig.initialDelay
  override val isEnabled: Boolean                          = jobConfig.enabled
  override val lockService: LockService                    = termsOfUseInvitationReminderLockService
  implicit val hc: HeaderCarrier                           = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    def getSubmission(applicationId: ApplicationId): Future[Option[Submission]]                                  = {
      submissionService.fetchLatest(applicationId)
    }
    def filterInvitations(invites: Seq[TermsOfUseInvitation], subs: List[Submission]): Seq[TermsOfUseInvitation] = {
      // Filter out any invitations where the corresponding submission has more
      // than one instance (i.e. remove ones that have been submitted already).
      // Note it's OK for an invitation not to have a submission.
      invites.filter(inv =>
        !subs.exists(sub => inv.applicationId == sub.applicationId) ||
          subs.exists(sub => inv.applicationId == sub.applicationId && sub.instances.size == 1)
      )
    }
    val reminderDueTime: Instant = Instant.now(clock).truncatedTo(MILLIS).plus(termsOfUseInvitationReminderInterval.toDays.toInt, ChronoUnit.DAYS)
    logger.info(s"Send terms of use reminders for invitations having status of EMAIL_SENT with dueBy earlier than $reminderDueTime")

    val result: Future[RunningOfJobSuccessful.type] = for {
      invitations        <- termsOfUseInvitationRepository.fetchByStatusBeforeDueBy(TermsOfUseInvitationState.EMAIL_SENT, reminderDueTime)
      submissions        <- Future.sequence(invitations.map(invite => getSubmission(invite.applicationId)).toList).map(_.flatten)
      filteredInvitations = filterInvitations(invitations, submissions)
      _                   = logger.info(s"Found ${filteredInvitations.size} invitations")
      _                  <- Future.sequence(filteredInvitations.map(sendReminderForInvitation(_)))
    } yield RunningOfJobSuccessful

    result.recoverWith {
      case e: Throwable => {
        logger.error(s"Scheduled job $name failed with an exception", e)
        Future.failed(RunningOfJobFailed(name, e))
      }
    }
  }

  private def sendReminderForInvitation(invite: TermsOfUseInvitation) = {
    logger.info(s"Send reminder for terms of use invitation for applicationId=${invite.applicationId.value}," +
      s"status='${invite.status}',dueBy='${invite.dueBy}}'")
    val E = EitherTHelper.make[String]

    (
      for {
        app       <- E.fromOptionF(applicationRepository.fetch(invite.applicationId), s"Couldn't find application with id=${invite.applicationId}")
        _         <- E.cond(!app.state.isDeleted, (), s"The application ${invite.applicationId} has been deleted")
        recipients = getRecipients(app)
        sent      <- E.liftF(emailConnector.sendNewTermsOfUseInvitation(invite.dueBy, app.name, recipients))
        _         <- E.liftF(termsOfUseInvitationRepository.updateReminderSent(invite.applicationId))
      } yield HasSucceeded
    ).value
  }

  private def getRecipients(app: ApplicationData): Set[LaxEmailAddress] = {
    app.admins.map(_.emailAddress)
  }
}

class TermsOfUseInvitationReminderJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "TermsOfUseInvitationReminderScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class TermsOfUseInvitationReminderJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, reminderInterval: FiniteDuration)
