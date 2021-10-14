/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services

import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiContext
import uk.gov.hmrc.thirdpartyapplication.modules.fraudprevention.domain.models.FraudPrevention
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

class DeriveContextSpec extends HmrcSpec with ApiIdentifierSyntax {

  val fpContext1 = FraudPrevention.contexts.head
  val fpSubs = List(fpContext1.asIdentifier, fpContext1.asIdentifier("2.0"), ApiContext.random.asIdentifier)
  val nonFpSubs = List(ApiContext.random.asIdentifier, ApiContext.random.asIdentifier, ApiContext.random.asIdentifier)
  import DeriveContext.Keys._

  "DerivceContext" when {
    "deriveFraudPrevention is called" should {
      "return 'Yes' when at least one suscription is a fraud prevention candidate" in {
        
        DeriveContext.deriveFraudPrevention(fpSubs) shouldBe "Yes"
      }
      "return 'No' when not a single suscription is a fraud prevention candidate" in {
        
        DeriveContext.deriveFraudPrevention(nonFpSubs) shouldBe "No"
      }
    }
  }

  "deriveFor is called" should {
    "return the appropriate context when one suscription is a fraud prevention candidate" in {
      DeriveContext.deriveFor(mock[ApplicationData], fpSubs) shouldBe Map(VAT_OR_ITSA -> "Yes", IN_HOUSE_SOFTWARE -> "Yes")
    }
    "return the appropriate context when no suscription is a fraud prevention candidate" in {
      DeriveContext.deriveFor(mock[ApplicationData], nonFpSubs) shouldBe Map(VAT_OR_ITSA -> "No", IN_HOUSE_SOFTWARE -> "Yes")
    }
  }
}
