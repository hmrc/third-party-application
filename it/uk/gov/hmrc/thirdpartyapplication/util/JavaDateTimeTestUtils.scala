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

package uk.gov.hmrc.thirdpartyapplication.util

import org.scalatest.matchers.should.Matchers._

import java.time.{Clock, LocalDateTime}
import java.time.temporal.ChronoField
import java.time.ZoneOffset

trait JavaDateTimeTestUtils {

  def timestampShouldBeApproximatelyNow(date: LocalDateTime, thresholdMillis: Int = 500, clock: Clock) = {
    val now                   = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
    val reasonableExpectation = now.minus(thresholdMillis, ChronoField.MILLI_OF_SECOND.getBaseUnit)

    withClue(s"timestamp $date was not within ${thresholdMillis}ms of ${now}") {
      date.isAfter(reasonableExpectation) shouldBe true
    }
  }
}
