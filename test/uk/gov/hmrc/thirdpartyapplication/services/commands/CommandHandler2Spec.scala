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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import cats.data.Validated
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import cats.data.NonEmptyChain

class CommandHandler2Spec extends HmrcSpec with ApplicationTestData {

  val VALID         = Validated.Valid(())
  val applicationId = ApplicationId.random
  val timestamp     = FixedClock.now
  val clientSecret  = ClientSecret("name", timestamp, hashedSecret = "hashed")
  val secretValue   = "somSecret"

  "appHasLessThanLimitOfSecrets" should {

    // Application with two client secrets
    val applicationData: ApplicationData = anApplicationData(applicationId)

    "pass when existing secrets are less than the limit" in {
      CommandHandler.appHasLessThanLimitOfSecrets(applicationData, 3) shouldBe VALID

    }
    "fail when existing secrets are at the limit" in {
      CommandHandler.appHasLessThanLimitOfSecrets(applicationData, 2) shouldBe Validated.Invalid(NonEmptyChain("Client secret limit has been exceeded"))

    }
  }
}
