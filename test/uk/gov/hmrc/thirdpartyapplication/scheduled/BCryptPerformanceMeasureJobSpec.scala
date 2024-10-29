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

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.thirdpartyapplication.helpers.StubLogger
import uk.gov.hmrc.thirdpartyapplication.util._

class BCryptPerformanceMeasureJobSpec extends AsyncHmrcSpec {

  trait Setup extends FixedClock {
    val testedWorkFactorRange: Seq[Int] = 5 to 10 // Only need to run faster hashes to as not to make test too slow
    val stubLogger                      = new StubLogger()

    val jobUnderTest: BCryptPerformanceMeasureJob = new BCryptPerformanceMeasureJob(stubLogger, clock) {
      override val workFactorRangeToTest: Seq[Int] = testedWorkFactorRange
    }
  }

  "Performance Measure Job" should {
    "Correctly log results" in new Setup {

      await(jobUnderTest.executeInMutex)

      stubLogger.infoMessages.size should be(2)
      stubLogger.infoMessages.head should be("[bcrypt Performance] Starting performance measurement")
      testedWorkFactorRange.foreach { testedWorkFactor =>
        stubLogger.infoMessages.last contains s"Hashing with Work Factor [$testedWorkFactor]"
      }
    }
  }
}
