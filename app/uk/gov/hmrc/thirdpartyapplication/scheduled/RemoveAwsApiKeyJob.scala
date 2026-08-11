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

import javax.inject.Inject
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.stream.Materializer

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.ApiGatewayStore

class RemoveAwsApiKeyJob @Inject() (
    removeAwsApiKeyJobLockService: RemoveAwsApiKeyJobLockService,
    applicationRepository: ApplicationRepository,
    apiGateway: ApiGatewayStore,
    jobConfig: RemoveAwsApiKeyJobConfig
  )(implicit val ec: ExecutionContext,
    mat: Materializer
  ) extends ScheduledMongoJob
    with ApplicationLogger
    with MongoJavatimeFormats.Implicits {

  override def name: String                 = "RemoveAwsApiKeyJob"
  override def isEnabled: Boolean           = jobConfig.enabled
  override def initialDelay: FiniteDuration = 5.minutes
  override def interval: FiniteDuration     = 24.hours
  override val lockService: LockService     = removeAwsApiKeyJobLockService

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    applicationRepository.processAll(removeApplicationAwsKey(jobConfig.dryRun))
      .flatMap(_ => removeOrphans())
  }

  def removeOrphans(): Future[RunningOfJobSuccessful] = Future.sequence(
    jobConfig.orphanedKeys.split(",").toList.map(key =>
      if (jobConfig.dryRun) {
        logger.info(s"Dry run - would otherwise remove orphaned AWS key (${key})")
        Future.successful(HasSucceeded)
      } else {
        apiGateway.deleteApplication(key)
      }
    )
  )
    .map(_ => RunningOfJobSuccessful)

  def removeApplicationAwsKey(dryRun: Boolean): StoredApplication => Unit = {
    application =>
      {
        if (application.wso2ApplicationName.isBlank() == false) {
          if (dryRun) {
            logger.info(s"Dry run - would otherwise remove AWS key (${application.wso2ApplicationName}) for Application: ${application.id}")
          } else {
            apiGateway.deleteApplication(application.wso2ApplicationName)
          }
        }
      }
  }
}

class RemoveAwsApiKeyJobLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockId: String                 = "RemoveAwsApiKey"
  override val lockRepository: LockRepository = repository
  override val ttl: Duration                  = 1.hours
}

case class RemoveAwsApiKeyJobConfig(enabled: Boolean, dryRun: Boolean, orphanedKeys: String)
