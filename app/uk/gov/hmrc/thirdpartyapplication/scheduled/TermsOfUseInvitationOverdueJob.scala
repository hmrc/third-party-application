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

import java.time.{Clock, Instant}
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import com.google.inject.Singleton

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow, EitherTHelper}
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.models.{HasSucceeded, TermsOfUseInvitationState}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, TermsOfUseInvitationRepository}

@Singleton
class TermsOfUseInvitationOverdueJob @Inject() (
    termsOfUseInvitationOverdueLockService: TermsOfUseInvitationOverdueJobLockService,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    applicationRepository: ApplicationRepository,
    val clock: Clock,
    jobConfig: TermsOfUseInvitationOverdueJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger with ClockNow {

  override def name: String                 = "TermsOfUseInvitationOverdueJob"
  override def interval: FiniteDuration     = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean           = jobConfig.enabled
  override val lockService: LockService     = termsOfUseInvitationOverdueLockService
  implicit val hc: HeaderCarrier            = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val overdueTime: Instant = instant()
    logger.info(s"Set terms of use overdue for invitations having status of EMAIL_SENT or REMINDER_EMAIL_SENT with dueBy earlier than $overdueTime")

    val result: Future[RunningOfJobSuccessful.type] = for {
      invitations <- termsOfUseInvitationRepository.fetchByStatusesBeforeDueBy(overdueTime, TermsOfUseInvitationState.EMAIL_SENT, TermsOfUseInvitationState.REMINDER_EMAIL_SENT)
      _            = logger.info(s"Scheduled job $name found ${invitations.size} invitations")
      _           <- Future.sequence(invitations.map(setInvitationOverdue(_)))
    } yield RunningOfJobSuccessful

    result.recoverWith {
      case e: Throwable => {
        logger.error(s"Scheduled job $name failed with an exception", e)
        Future.failed(RunningOfJobFailed(name, e))
      }
    }
  }

  private def setInvitationOverdue(invite: TermsOfUseInvitation) = {
    logger.info(s"Set terms of use invitation overdue for applicationId=${invite.applicationId.value}," +
      s"status='${invite.status}',dueBy='${invite.dueBy}}'")
    val E = EitherTHelper.make[String]

    (
      for {
        app     <- E.fromOptionF(applicationRepository.fetch(invite.applicationId), s"Couldn't find application with id=${invite.applicationId}")
        _       <- E.cond(!app.state.isDeleted, (), s"The application ${invite.applicationId} has been deleted")
        updated <- E.liftF(termsOfUseInvitationRepository.updateState(invite.applicationId, TermsOfUseInvitationState.OVERDUE))
      } yield HasSucceeded
    ).value
  }
}

class TermsOfUseInvitationOverdueJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "TermsOfUseInvitationOverdueScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class TermsOfUseInvitationOverdueJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)
