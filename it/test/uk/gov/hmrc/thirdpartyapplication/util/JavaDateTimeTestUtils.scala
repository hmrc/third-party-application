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

import java.time.temporal.{ChronoField, ChronoUnit}
import java.time.{Clock, Instant}

import org.scalatest.matchers.should.Matchers._

trait JavaDateTimeTestUtils {

  def timestampShouldBeApproximatelyNow(date: Instant, thresholdMillis: Int = 500, clock: Clock) = {
    val anInstant             = clock.instant().truncatedTo(ChronoUnit.MILLIS)
    val reasonableExpectation = anInstant.minus(thresholdMillis, ChronoField.MILLI_OF_SECOND.getBaseUnit)

    withClue(s"timestamp $date was not within ${thresholdMillis}ms of ${anInstant}") {
      date.isAfter(reasonableExpectation) shouldBe true
    }
  }
}
