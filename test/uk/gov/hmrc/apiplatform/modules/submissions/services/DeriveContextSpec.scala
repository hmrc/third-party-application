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

package uk.gov.hmrc.apiplatform.modules.submissions.services

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.SellResellOrDistribute
import uk.gov.hmrc.apiplatform.modules.fraudprevention.domain.models.FraudPrevention
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.AskWhen.Context.Keys
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationStateExamples
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{HmrcSpec, UpliftRequestSamples}

class DeriveContextSpec extends HmrcSpec with ApiIdentifierSyntax with UpliftRequestSamples {

  val fpContext1 = FraudPrevention.contexts.head
  val fpSubs     = List(fpContext1.asIdentifier, fpContext1.asIdentifier("2.0"), ApiContext.random.asIdentifier)
  val nonFpSubs  = List(ApiContext.random.asIdentifier, ApiContext.random.asIdentifier, ApiContext.random.asIdentifier)
  val newUplift  = "No"

  "DeriveContext" when {
    "deriveFraudPrevention is called" should {
      "return 'Yes' when at least one subscription is a fraud prevention candidate" in {

        DeriveContext.deriveFraudPrevention(newUplift, fpSubs) shouldBe "Yes"
      }
      "return 'No' when not a single subscription is a fraud prevention candidate" in {

        DeriveContext.deriveFraudPrevention(newUplift, nonFpSubs) shouldBe "No"
      }
      "return 'No' when new uplift and at least one subscription is a fraud prevention candidate" in {

        DeriveContext.deriveFraudPrevention("Yes", fpSubs) shouldBe "No"
      }
    }
  }

  "deriveFor is called" should {
    "return the appropriate context when one subscription is a fraud prevention candidate" in {
      val aMock: ApplicationData = mock[ApplicationData]
      when(aMock.sellResellOrDistribute).thenReturn(Some(SellResellOrDistribute("Yes")))
      when(aMock.state).thenReturn(ApplicationStateExamples.testing)

      DeriveContext.deriveFor(aMock, fpSubs) shouldBe Map(Keys.VAT_OR_ITSA -> "Yes", Keys.IN_HOUSE_SOFTWARE -> "No", Keys.NEW_TERMS_OF_USE_UPLIFT -> "No")
    }
    "return the appropriate context when no subscription is a fraud prevention candidate" in {
      val aMock: ApplicationData = mock[ApplicationData]
      when(aMock.sellResellOrDistribute).thenReturn(Some(SellResellOrDistribute("No")))
      when(aMock.state).thenReturn(ApplicationStateExamples.testing)

      DeriveContext.deriveFor(aMock, nonFpSubs) shouldBe Map(Keys.VAT_OR_ITSA -> "No", Keys.IN_HOUSE_SOFTWARE -> "Yes", Keys.NEW_TERMS_OF_USE_UPLIFT -> "No")
    }
    "return the appropriate context when the application is already in production" in {
      val aMock: ApplicationData = mock[ApplicationData]
      when(aMock.sellResellOrDistribute).thenReturn(Some(SellResellOrDistribute("Yes")))
      when(aMock.state).thenReturn(ApplicationStateExamples.production("requesterEmail", "requesterName"))

      DeriveContext.deriveFor(aMock, fpSubs) shouldBe Map(Keys.VAT_OR_ITSA -> "No", Keys.IN_HOUSE_SOFTWARE -> "No", Keys.NEW_TERMS_OF_USE_UPLIFT -> "Yes")
    }
  }
}
