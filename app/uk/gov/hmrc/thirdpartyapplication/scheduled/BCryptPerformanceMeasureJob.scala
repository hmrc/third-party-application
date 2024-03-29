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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

import com.github.t3hnar.bcrypt._
import com.google.inject.Singleton

import play.api.LoggerLike

import uk.gov.hmrc.apiplatform.modules.common.services.{ClockNow, SimpleTimer, TimedValue}
import uk.gov.hmrc.apiplatform.modules.scheduling.ExclusiveScheduledJob

@Singleton
class BCryptPerformanceMeasureJob @Inject() (logger: LoggerLike, val clock: Clock) extends ExclusiveScheduledJob with ClockNow with SimpleTimer {

  override def name: String                 = "bcrypt Performance Measurement"
  override def initialDelay: FiniteDuration = FiniteDuration(10, TimeUnit.MINUTES)
  override def interval: FiniteDuration     = FiniteDuration(4, TimeUnit.HOURS)

  val workFactorRangeToTest: Seq[Int] = 5 to 15

  override def executeInMutex(implicit ec: ExecutionContext): Future[Result] = {
    val stringToHash: String = UUID.randomUUID().toString

    logger.info("[bcrypt Performance] Starting performance measurement")

    val timings = workFactorRangeToTest.map(workFactor => {

      val timedValue: TimedValue[String] = timeThis(() => stringToHash.bcrypt(workFactor))

      s"Hashing with Work Factor [$workFactor] took [${timedValue.duration.toMillis()}ms]"

    }).mkString("\n")

    logger.info(s"[bcrypt Performance] Performance measurement results:\n$timings")

    Future.successful(Result("Done"))
  }
}
