/*
 * Copyright 2019 HM Revenue & Customs
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
import org.joda.time.Duration
import play.api.{Logger, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}
import uk.gov.hmrc.thirdpartyapplication.connector.Wso2ApiStoreConnector
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class ReconcileRateLimitsScheduledJob @Inject()(val lockKeeper: ReconcileRateLimitsJobLockKeeper,
                                                applicationRepository: ApplicationRepository,
                                                wso2ApiStoreConnector: Wso2ApiStoreConnector,
                                                jobConfig: ReconcileRateLimitsJobConfig,
                                                logger: LoggerLike = Logger) extends ScheduledMongoJob {

  override def name: String = "ReconcileRateLimitsScheduledJob"
  override def interval: FiniteDuration = jobConfig.interval
  override def initialDelay: FiniteDuration = jobConfig.initialDelay

  override def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    logger.info("Starting Rate Limit Reconciliation Job")

    applicationRepository.processAll(
      tpaApplication =>
        for {
          wso2Cookie <- wso2ApiStoreConnector.login(tpaApplication.wso2Username, tpaApplication.wso2Password)
          _ <- reconcileApplicationRateLimit(wso2Cookie, tpaApplication)
          result <- wso2ApiStoreConnector.logout(wso2Cookie)
        } yield result)
    .map { _ =>
      logger.info("Finishing Rate Limit Reconciliation Job")
      RunningOfJobSuccessful
    }
  }

  def reconcileApplicationRateLimit(wso2Cookie: String, tpaApplication: ApplicationData)
                                   (implicit ec: ExecutionContext, headerCarrier: HeaderCarrier): Future[Unit] = {

    def message(wso2ApplicationRateLimit: RateLimitTier, status: String): String =
      s"Rate Limits for Application [${tpaApplication.name} (${tpaApplication.id})] are - " +
        s"TPA: [${tpaApplication.rateLimitTier.getOrElse("NONE")}], WSO2: [$wso2ApplicationRateLimit] - $status"

    wso2ApiStoreConnector.getApplicationRateLimitTier(wso2Cookie, tpaApplication.wso2ApplicationName) map {
      wso2ApplicationRateLimit =>
        tpaApplication.rateLimitTier match {
          case None =>
            logger.warn(message(wso2ApplicationRateLimit, "MISMATCH"))
          case Some(tpaApplicationRateLimit) =>
            if (tpaApplicationRateLimit == wso2ApplicationRateLimit) {
              logger.debug(message(wso2ApplicationRateLimit, "MATCH"))
            } else {
              logger.warn(message(wso2ApplicationRateLimit, "MISMATCH"))
            }
        }
    }
  }
}

class ReconcileRateLimitsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "ReconcileRateLimitsScheduledJob"

  override val forceLockReleaseAfter: Duration = Duration.standardHours(1)

}

case class ReconcileRateLimitsJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)