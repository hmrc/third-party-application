/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.thirdpartyapplication.scheduled.RateLimitMatch.RateLimitMatch

import scala.concurrent.Future.successful
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

    for {
      tpaApplications <- applicationRepository.findAll()
      results <- processApplicationsOneByOne(tpaApplications)
      _ = logger.info(
        s"Finishing Rate Limit Reconciliation Job - " +
          s"${results._1 + results._2 + results._3} Applications Processed (${results._1} Matches, ${results._2} Mismatches, ${results._3} Failures)")
    } yield RunningOfJobSuccessful
  }

  //Processing applications 1 by 1 as WSO2 times out when too many subscriptions calls are made simultaneously
  private def processApplicationsOneByOne(tpaApplications: Seq[ApplicationData], processed: (Int, Int, Int) = (0, 0, 0))
                                         (implicit hc: HeaderCarrier, ec: ExecutionContext): Future[(Int, Int, Int)] = {
    tpaApplications match {
      case app :: tail =>
        processApplication(app)
          .flatMap {
            case RateLimitMatch.MATCH => processApplicationsOneByOne(tail, (processed._1 + 1, processed._2, processed._3))
            case RateLimitMatch.MISMATCH => processApplicationsOneByOne(tail, (processed._1, processed._2 + 1, processed._3))
            case RateLimitMatch.FAILURE => processApplicationsOneByOne(tail, (processed._1, processed._2, processed._3 + 1))
          }
      case Nil => successful(processed)
    }
  }

  private def processApplication(tpaApplication: ApplicationData)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[RateLimitMatch] = {
    val rateLimitMatches = for {
      wso2Cookie <- wso2ApiStoreConnector.login(tpaApplication.wso2Username, tpaApplication.wso2Password)
      rateLimitMatches <- reconcileApplicationRateLimit(wso2Cookie, tpaApplication)
      _ <- wso2ApiStoreConnector.logout(wso2Cookie)
    } yield rateLimitMatches

    rateLimitMatches.recoverWith {
      case e =>
        logger.error(s"Failed to process Application [${tpaApplication.name} (${tpaApplication.id})]", e)
        Future.successful(RateLimitMatch.FAILURE)
    }
  }

  private def reconcileApplicationRateLimit(wso2Cookie: String, tpaApplication: ApplicationData)
                                   (implicit ec: ExecutionContext, headerCarrier: HeaderCarrier): Future[RateLimitMatch] = {

    def logResult(wso2ApplicationRateLimit: RateLimitTier, rateLimitMatches: RateLimitMatch): Unit = {
      val message = s"Rate Limits for Application [${tpaApplication.name} (${tpaApplication.id})] are - " +
        s"TPA: [${tpaApplication.rateLimitTier.getOrElse("NONE")}], WSO2: [$wso2ApplicationRateLimit] - $rateLimitMatches"

      rateLimitMatches match {
        case RateLimitMatch.MATCH => logger.debug(message)
        case RateLimitMatch.MISMATCH => logger.warn(message)
      }
    }

    def repairMismatchedRateLimits(rateLimitToSet: RateLimitTier): Unit = {
      logger.info(s"Setting Rate Limit for Application [${tpaApplication.name} (${tpaApplication.id})] to $rateLimitToSet")
      applicationRepository.updateApplicationRateLimit(tpaApplication.id, rateLimitToSet)
    }

    wso2ApiStoreConnector.getApplicationRateLimitTier(wso2Cookie, tpaApplication.wso2ApplicationName) map {
      wso2ApplicationRateLimit => {
        if(tpaApplication.rateLimitTier.isDefined && tpaApplication.rateLimitTier.get == wso2ApplicationRateLimit) {
          logResult(wso2ApplicationRateLimit, RateLimitMatch.MATCH)
          RateLimitMatch.MATCH
        } else {
          logResult(wso2ApplicationRateLimit, RateLimitMatch.MISMATCH)
          repairMismatchedRateLimits(wso2ApplicationRateLimit)
          RateLimitMatch.MISMATCH
        }
      }
    }
  }
}

object RateLimitMatch extends Enumeration {
  type RateLimitMatch = Value

  val MATCH, MISMATCH, FAILURE = Value
}

class ReconcileRateLimitsJobLockKeeper @Inject()(mongo: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)

  override def lockId: String = "ReconcileRateLimitsScheduledJob"

  override val forceLockReleaseAfter: Duration = Duration.standardHours(1)

}

case class ReconcileRateLimitsJobConfig(initialDelay: FiniteDuration, interval: FiniteDuration, enabled: Boolean)