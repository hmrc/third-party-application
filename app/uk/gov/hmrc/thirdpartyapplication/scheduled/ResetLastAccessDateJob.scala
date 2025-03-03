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

import java.time.{LocalDate, ZoneOffset}
import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import org.mongodb.scala.model.Updates

import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

class ResetLastAccessDateJob @Inject() (
    resetLastAccessDateJobLockService: ResetLastAccessDateJobLockService,
    applicationRepository: ApplicationRepository,
    jobConfig: ResetLastAccessDateJobConfig
  )(implicit val ec: ExecutionContext
  ) extends ScheduledMongoJob
    with ApplicationLogger
    with MongoJavatimeFormats.Implicits {

  import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper._

  override def name: String                 = "ResetLastAccessDateJob"
  override def isEnabled: Boolean           = jobConfig.enabled
  override def initialDelay: FiniteDuration = 5.minutes
  override def interval: FiniteDuration     = 24.hours
  override val lockService: LockService     = resetLastAccessDateJobLockService

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    applicationRepository.processAll(updateLastAccessDate(jobConfig.noLastAccessDateBefore, jobConfig.dryRun))
      .map(_ => RunningOfJobSuccessful)
  }

  def updateLastAccessDate(earliestLastAccessDate: LocalDate, dryRun: Boolean): StoredApplication => Unit = {

    def updateApplicationRecord(applicationId: ApplicationId, applicationName: ApplicationName) = {
      if (dryRun) {
        logger.info(s"[ResetLastAccessDateJob (Dry Run)]: Application [$applicationName (${applicationId})] would have had lastAccess set to [$earliestLastAccessDate]")
      } else {
        logger.info(s"[ResetLastAccessDateJob]: Setting lastAccess of application [$applicationName (${applicationId})] to [$earliestLastAccessDate]")
        applicationRepository.updateApplication(applicationId, Updates.set("lastAccess", Codecs.toBson(earliestLastAccessDate.asInstant)))
      }
    }

    application => {
      application.lastAccess match {
        case Some(lastAccessDate) =>
          val lastAccessAsLocalDate = lastAccessDate.atOffset(ZoneOffset.UTC).toLocalDate()
          if (lastAccessAsLocalDate.isBefore(earliestLastAccessDate))
            updateApplicationRecord(application.id, application.name)
          else
            ()
        case None                 => updateApplicationRecord(application.id, application.name)
      }
    }
  }
}

class ResetLastAccessDateJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "ResetLastAccessDate"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class ResetLastAccessDateJobConfig(noLastAccessDateBefore: LocalDate, enabled: Boolean, dryRun: Boolean)
