package uk.gov.hmrc.thirdpartyapplication.util

import org.joda.time.DateTime
import org.scalatest.matchers.should.Matchers._

trait DateTimeUtils {
  def timestampShouldBeApproximatelyNow(date: DateTime, thresholdMillis: Int = 500) = {
    val now = DateTime.now()
    val reasonableExpectation = now.minusMillis(thresholdMillis)
    withClue(s"timestamp $date was not within ${thresholdMillis}ms of $now") {
      date.isAfter(reasonableExpectation) shouldBe true
    }
  }
}
