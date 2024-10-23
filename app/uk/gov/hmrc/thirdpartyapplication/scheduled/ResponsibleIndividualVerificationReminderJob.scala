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

import java.time.temporal.ChronoUnit.SECONDS
import java.time.{Clock, Instant}
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import cats.data.OptionT
import cats.implicits._
import com.google.inject.Singleton

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ResponsibleIndividual
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.{Application, HasSucceeded}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaborators

@Singleton
class ResponsibleIndividualVerificationReminderJob @Inject() (
    responsibleIndividualVerificationReminderJobLockService: ResponsibleIndividualVerificationReminderJobLockService,
    repository: ResponsibleIndividualVerificationRepository,
    emailConnector: EmailConnector,
    applicationService: ApplicationService,
    val clock: Clock,
    jobConfig: ResponsibleIndividualVerificationReminderJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob with ApplicationLogger {

  override def name: String                 = "ResponsibleIndividualVerificationReminderJob"
  override def interval: FiniteDuration     = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean           = jobConfig.enabled
  implicit val hc: HeaderCarrier            = HeaderCarrier()
  override val lockService: LockService     = responsibleIndividualVerificationReminderJobLockService

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val remindIfCreatedBeforeNow                    = Instant.now(clock).minus(jobConfig.reminderInterval.toSeconds, SECONDS)
    val result: Future[RunningOfJobSuccessful.type] = for {
      remindersDue <-
        repository.fetchByTypeStateAndAge(ResponsibleIndividualVerification.VerificationTypeToU, ResponsibleIndividualVerificationState.INITIAL, remindIfCreatedBeforeNow)
      _             = logger.info(s"Scheduled job $name found ${remindersDue.size} records")
      _            <- Future.sequence(remindersDue.map(sendReminderEmailsAndUpdateStatus(_)))
    } yield RunningOfJobSuccessful
    result.recoverWith {
      case e: Throwable => {
        logger.error(s"Scheduled job $name failed with an exception", e)
        Future.failed(RunningOfJobFailed(name, e))
      }
    }
  }

  private def sendReminderEmailsAndUpdateStatus(verificationDueForReminder: ResponsibleIndividualVerification) = {
    (for {
      app            <- applicationService.fetch(verificationDueForReminder.applicationId)
      ri             <- OptionT.fromOption[Future](getResponsibleIndividual(app))
      requesterName  <- OptionT.fromOption[Future](getRequesterName(app))
      requesterEmail <- OptionT.fromOption[Future](getRequesterEmail(app))
      _              <- OptionT.liftF(emailConnector.sendVerifyResponsibleIndividualNotification(
                          ri.fullName.value,
                          ri.emailAddress,
                          app.name,
                          requesterName,
                          verificationDueForReminder.id.value
                        ))
      _              <- OptionT.liftF(emailConnector.sendVerifyResponsibleIndividualReminderToAdmin(ri.fullName.value, requesterEmail, app.name, requesterName))
      _              <- OptionT.liftF(repository.updateState(verificationDueForReminder.id, ResponsibleIndividualVerificationState.REMINDERS_SENT))
    } yield HasSucceeded).value
  }

  private def getResponsibleIndividual(app: ApplicationWithCollaborators): Option[ResponsibleIndividual] = {
    app.access match {
      case Access.Standard(_, _, _, _, _, Some(importantSubmissionData)) => Some(importantSubmissionData.responsibleIndividual)
      case _                                                             => None
    }
  }

  private def getRequesterName(app: ApplicationWithCollaborators): Option[String] = {
    app.state.requestedByName
  }

  private def getRequesterEmail(app: ApplicationWithCollaborators): Option[LaxEmailAddress] = {
    app.state.requestedByEmailAddress.map(LaxEmailAddress(_)) // This should be an email address for these operations but this is not provable
  }
}

class ResponsibleIndividualVerificationReminderJobLockService @Inject() (repository: LockRepository)
    extends LockService {
  override val lockId: String                 = "ResponsibleIndividualVerificationReminderScheduler"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class ResponsibleIndividualVerificationReminderJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, reminderInterval: FiniteDuration, enabled: Boolean)
