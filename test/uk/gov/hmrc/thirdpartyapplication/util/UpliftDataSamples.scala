/*
 * Copyright 2022 HM Revenue & Customs
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

import uk.gov.hmrc.thirdpartyapplication.domain.models.SellResellOrDistribute
import uk.gov.hmrc.thirdpartyapplication.models.UpliftRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifier

trait UpliftRequestSamples {
  val sellResellOrDistribute      = SellResellOrDistribute("Yes")
  val doNotSellResellOrDistribute = SellResellOrDistribute("No")

  def makeUpliftRequest(subscriptions: Set[ApiIdentifier]): UpliftRequest = UpliftRequest(sellResellOrDistribute, subscriptions)
  def makeUpliftRequest(subscriptions: ApiIdentifier*): UpliftRequest     = makeUpliftRequest(subscriptions.toSet)
}
