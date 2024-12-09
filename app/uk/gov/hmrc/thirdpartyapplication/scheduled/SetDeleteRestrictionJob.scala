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
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.model.Updates

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationName, DeleteRestriction}
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiPlatformEventsConnector, DisplayEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

class SetDeleteRestrictionJob @Inject() (
    setDeleteRestrictionJobLockService: SetDeleteRestrictionJobLockService,
    applicationRepository: ApplicationRepository,
    eventsConnector: ApiPlatformEventsConnector,
    val clock: Clock,
    jobConfig: SetDeleteRestrictionJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob
    with ApplicationLogger
    with ClockNow
    with MongoJavatimeFormats.Implicits {

  override def name: String                 = "SetDeleteRestrictionJob"
  override def isEnabled: Boolean           = jobConfig.enabled
  override def initialDelay: FiniteDuration = 1.minutes
  override def interval: FiniteDuration     = 100.days
  override val lockService: LockService     = setDeleteRestrictionJobLockService
  implicit val hc: HeaderCarrier            = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logger.info("Running SetDeleteRestrictionJob")
    applicationRepository.processAll(setDeleteRestriction())
      .map(_ => RunningOfJobSuccessful)
  }

  private def getDoNotDelete(event: Option[DisplayEvent]): DeleteRestriction = {
    event match {
      case None      => DeleteRestriction.DoNotDelete("Set by process - Reason not found", Actors.ScheduledJob(name), instant())
      case Some(evt) => DeleteRestriction.DoNotDelete(evt.metaData.mkString, evt.actor, evt.eventDateTime)
    }
  }

  private def getDeleteRestriction(applicationId: ApplicationId, allowAutoDelete: Boolean): Future[DeleteRestriction] = {
    if (allowAutoDelete) {
      Future.successful(DeleteRestriction.NoRestriction)
    } else {
      eventsConnector.query(applicationId, Some("APP_LIFECYCLE"), None)
        .map(events => events.find(e => e.eventType == "Application auto delete blocked"))
        .map(getDoNotDelete(_))
    }
  }

  def setDeleteRestriction(): StoredApplication => Unit = {

    def updateApplicationRecord(applicationId: ApplicationId, applicationName: ApplicationName, allowAutoDelete: Boolean) = {
      logger.info(s"[SetDeleteRestrictionJob]: Setting deleteRestriction of application [$applicationName (${applicationId})]")
      for {
        deleteRestriction <- getDeleteRestriction(applicationId, allowAutoDelete)
        savedApp          <- applicationRepository.updateApplication(applicationId, Updates.set("deleteRestriction", Codecs.toBson(deleteRestriction)))
        _                  = logger.info(s"[SetDeleteRestrictionJob]: Set deleteRestriction of application [$applicationName (${applicationId})] to [$deleteRestriction]")
      } yield savedApp
    }

    application => {
      updateApplicationRecord(application.id, application.name, application.allowAutoDelete)
    }
  }
}

class SetDeleteRestrictionJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "SetDeleteRestriction"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class SetDeleteRestrictionJobConfig(enabled: Boolean)
