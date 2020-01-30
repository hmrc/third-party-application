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

import java.util.concurrent.TimeUnit

import javax.inject.Inject
import net.ceedubs.ficus.Ficus._
import org.joda.time.{DateTime, DateTimeZone, Duration, LocalTime}
import play.api.{Configuration, Logger, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.{LockKeeper, LockRepository}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

abstract class TimedJob @Inject()(override val name: String,
                                  configuration: Configuration,
                                  mongo: ReactiveMongoComponent,
                                  val logger: LoggerLike = Logger) extends ScheduledMongoJob with TimedJobConfigReaders {

  override val lockKeeper: LockKeeper = mongoLockKeeper(mongo)

  val jobConfig: TimedJobConfig = configuration.underlying.as[TimedJobConfig](name)

  def isEnabled: Boolean = jobConfig.enabled

  override def initialDelay: FiniteDuration = jobConfig.startTime match {
    case Some(startTime) => calculateInitialDelay(startTime.startTime)
    case _ => FiniteDuration(0, TimeUnit.MILLISECONDS)
  }

  override def interval: FiniteDuration = jobConfig.executionInterval.interval

  def calculateInitialDelay(timeOfFirstRun: LocalTime): FiniteDuration = {
    val currentDateTime = DateTime.now(DateTimeZone.UTC)
    val timeToday = timeOfFirstRun.toDateTimeToday(DateTimeZone.UTC)
    val nextInstanceOfTime = if (timeToday.isBefore(currentDateTime)) timeToday.plusDays(1) else timeToday
    val millisecondsToFirstRun = nextInstanceOfTime.getMillis - currentDateTime.getMillis

    FiniteDuration(millisecondsToFirstRun, TimeUnit.MILLISECONDS)
  }

  def mongoLockKeeper(mongo: ReactiveMongoComponent): LockKeeper = new LockKeeper {
    override def repo: LockRepository = new LockRepository()(mongo.mongoConnector.db)
    override def lockId: String = s"$name-Lock"
    override val forceLockReleaseAfter: Duration = Duration.standardHours(1)
  }

  override final def runJob(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    logger.info(s"Starting scheduled job [$name].")
    val result = functionToExecute()
    logger.info(s"Scheduled job [$name] complete.")

    result
  }

  def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful]
}

class StartTime(val startTime: LocalTime) extends AnyVal
class ExecutionInterval(val interval: FiniteDuration) extends AnyVal

case class TimedJobConfig(startTime: Option[StartTime], executionInterval: ExecutionInterval, enabled: Boolean) {
  override def toString = s"TimedJobConfig{startTime=${startTime.getOrElse("Not Specified")} interval=${executionInterval.interval} enabled=$enabled}"
}
