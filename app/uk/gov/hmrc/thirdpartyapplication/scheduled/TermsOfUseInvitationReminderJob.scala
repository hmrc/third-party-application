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
    emailConnector: EmailConnector,
    clock: Clock,
    jobConfig: TermsOfUseInvitationReminderJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  val termsOfUseInvitationReminderInterval: FiniteDuration       = jobConfig.reminderInterval
  override def name: String                                      = "TermsOfUseInvitationReminderJob"
  override def interval: FiniteDuration                          = jobConfig.interval
  override def initialDelay: FiniteDuration                      = jobConfig.initialDelay
  override val isEnabled: Boolean                                = jobConfig.enabled
  override val lockService: LockService                          = termsOfUseInvitationReminderLockService
  implicit val hc: HeaderCarrier                                 = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val reminderDueTime: Instant = Instant.now().truncatedTo(MILLIS).plus(termsOfUseInvitationReminderInterval.toDays.toInt, ChronoUnit.DAYS)
    logger.info(s"Send terms of use reminders for invitations having status of EMAIL_SENT with dueBy earlier than $reminderDueTime")

    val result: Future[RunningOfJobSuccessful.type] = for {
      invitations <- termsOfUseInvitationRepository.fetchByStatusBeforeDueBy(TermsOfUseInvitationState.EMAIL_SENT, reminderDueTime)
      _            = logger.info(s"Found ${invitations.size} invitations")
      _           <- Future.sequence(invitations.map(sendReminderForInvitation(_)))
    } yield RunningOfJobSuccessful

    result.recoverWith {
      case e: Throwable => Future.failed(RunningOfJobFailed(name, e))
    }
  }

  private def sendReminderForInvitation(invite: TermsOfUseInvitation) = {
    logger.info(s"Send reminder for terms of use invitation for applicationId=${invite.applicationId.value}," +
      s"status='${invite.status}',dueBy='${invite.dueBy}}'")
    val E = EitherTHelper.make[String]

    (
      for {
        app        <- E.fromOptionF(applicationRepository.fetch(invite.applicationId), s"Couldn't find application with id=${invite.applicationId}")
        recipients =  getRecipients(app)
        sent       <- E.liftF(emailConnector.sendNewTermsOfUseInvitation(invite.dueBy, app.name, recipients))
        _          <- E.liftF(termsOfUseInvitationRepository.updateReminderSent(invite.applicationId))
      } yield HasSucceeded
    ).value
  }

  private def getRecipients(app: ApplicationData): Set[LaxEmailAddress] = {
    app.collaborators.map(_.emailAddress)
  }
}

class TermsOfUseInvitationReminderJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "TermsOfUseInvitationReminderScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class TermsOfUseInvitationReminderJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean, reminderInterval: FiniteDuration)
