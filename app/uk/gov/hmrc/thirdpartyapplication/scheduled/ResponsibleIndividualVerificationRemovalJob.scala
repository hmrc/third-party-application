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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import cats.data.OptionT
import com.google.inject.Singleton

import javax.inject.Inject
import org.joda.time.Duration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ResponsibleIndividual, Standard}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationResponse, HasSucceeded}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import java.time.{Clock, LocalDateTime}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import cats.implicits._
import uk.gov.hmrc.http.HeaderCarrier
import java.time.temporal.ChronoUnit.SECONDS


@Singleton
class ResponsibleIndividualVerificationRemovalJob @Inject()(val lockKeeper: ResponsibleIndividualVerificationRemovalJobLockKeeper,
                                                             repository: ResponsibleIndividualVerificationRepository,
                                                             emailConnector: EmailConnector,
                                                             applicationService: ApplicationService,
                                                             val clock: Clock,
                                                             jobConfig: ResponsibleIndividualVerificationRemovalJobConfig)(implicit val ec: ExecutionContext) extends ScheduledMongoJob with ApplicationLogger {

  override def name: String = "ResponsibleIndividualVerificationRemovalJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay
  override val isEnabled: Boolean = jobConfig.enabled
  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val removeIfCreatedBeforeNow = LocalDateTime.now(clock).minus(jobConfig.removalInterval.toSeconds, SECONDS)
    val result: Future[RunningOfJobSuccessful.type] = for {
      removalsDue <- repository.fetchByStateAndAge(ResponsibleIndividualVerificationState.REMINDERS_SENT, removeIfCreatedBeforeNow)
      _           <- Future.sequence(removalsDue.map(sendRemovalEmailAndRemoveRecord(_)))
    } yield RunningOfJobSuccessful
    result.recoverWith {
      case e: Throwable => {
        logger.error(s"Scheduled job $name failed with an exception", e)
        Future.failed(RunningOfJobFailed(name, e))
      }
    }
  }

  private def sendRemovalEmailAndRemoveRecord(verificationDueForRemoval: ResponsibleIndividualVerification) = {
    (for {
      app            <- applicationService.fetch(verificationDueForRemoval.applicationId)
      ri             <- OptionT.fromOption[Future](getResponsibleIndividual(app))
      requesterName  <- OptionT.fromOption[Future](getRequesterName(app))
      requesterEmail <- OptionT.fromOption[Future](getRequesterEmail(app))
      _              <- OptionT.liftF(emailConnector.sendResponsibleIndividualDidNotVerify(ri.fullName.value, requesterEmail, app.name, requesterName))
      _              <- OptionT.liftF(repository.delete(verificationDueForRemoval.id))
    } yield HasSucceeded).value
  }

  private def getResponsibleIndividual(app: ApplicationResponse): Option[ResponsibleIndividual] = {
    app.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => Some(importantSubmissionData.responsibleIndividual)
      case _ => None
    }
  }

  private def getRequesterName(app: ApplicationResponse): Option[String] = {
    app.state.requestedByName
  }
  private def getRequesterEmail(app: ApplicationResponse): Option[String] = {
    app.state.requestedByEmailAddress
  }
}

class ResponsibleIndividualVerificationRemovalJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "ResponsibleIndividualVerificationRemovalScheduler"

  override val forceLockReleaseAfter: Duration = Duration.standardMinutes(60) // scalastyle:off magic.number
}

case class ResponsibleIndividualVerificationRemovalJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, removalInterval: FiniteDuration, enabled: Boolean)
