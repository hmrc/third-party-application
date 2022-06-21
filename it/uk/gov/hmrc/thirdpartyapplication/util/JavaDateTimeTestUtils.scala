package uk.gov.hmrc.thirdpartyapplication.util

import org.scalatest.matchers.should.Matchers._

import java.time.{Clock, LocalDateTime}
import java.time.temporal.ChronoField

trait JavaDateTimeTestUtils {

  def timestampShouldBeApproximatelyNow(date: LocalDateTime, thresholdMillis: Int = 500, clock: Clock) = {
    val now                   = LocalDateTime.now(clock)
    val reasonableExpectation = now.minus(thresholdMillis, ChronoField.MILLI_OF_SECOND.getBaseUnit)

    withClue(s"timestamp $date was not within ${thresholdMillis}ms of $now") {
      date.isAfter(reasonableExpectation) shouldBe true
    }
  }
}
