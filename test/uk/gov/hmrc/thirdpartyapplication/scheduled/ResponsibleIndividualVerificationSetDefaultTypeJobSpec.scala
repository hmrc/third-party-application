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

import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ResponsibleIndividualVerificationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import java.time.{Clock, LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{DAYS, FiniteDuration, MINUTES}

class ResponsibleIndividualVerificationSetDefaultTypeJobSpec extends AsyncHmrcSpec with BeforeAndAfterAll with ApplicationStateUtil {

  trait Setup extends ResponsibleIndividualVerificationRepositoryMockModule {

    val mockLockKeeper = mock[ResponsibleIndividualVerificationSetDefaultTypeJobLockService]
    val timeNow        = LocalDateTime.now
    val fixedClock     = Clock.fixed(timeNow.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    val initialDelay = FiniteDuration(1, MINUTES)
    val interval     = FiniteDuration(20, DAYS)
    val jobConfig    = ResponsibleIndividualVerificationSetDefaultTypeJobConfig(initialDelay, interval, true)

    val job = new ResponsibleIndividualVerificationSetDefaultTypeJob(
      mockLockKeeper,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      fixedClock,
      jobConfig
    )
  }

  "ResponsibleIndividualVerificationSetDefaultTypeJob" should {
    "sets verificationType field in database" in new Setup {
      ResponsibleIndividualVerificationRepositoryMock.UpdateSetDefaultVerificationType.thenReturnSuccess()

      await(job.runJob)

      ResponsibleIndividualVerificationRepositoryMock.UpdateSetDefaultVerificationType.verifyCalledWith("termsOfUse")
    }

  }
}
