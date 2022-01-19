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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.services

import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifier
import uk.gov.hmrc.thirdpartyapplication.modules.fraudprevention.domain.models.FraudPrevention
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.AskWhen.Context
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.AskWhen.Context.Keys

object DeriveContext {

  def yesNoFromBoolean(b: Boolean) = if(b) "Yes" else "No"

  def deriveFraudPrevention(subscriptions: List[ApiIdentifier]): String =  {
    val appContexts = subscriptions.map(_.context.value).toSet
    yesNoFromBoolean(appContexts.intersect(FraudPrevention.contexts).nonEmpty)
  }

  def deriveFor(application: ApplicationData, subscriptions: List[ApiIdentifier]): Context = {
    
    val resell = application.upliftData.fold("No")(ud => ud.sellResellOrDistribute.answer)
    val inHouse = if(resell == "Yes") "No" else "Yes"

    Map(
      Keys.VAT_OR_ITSA -> deriveFraudPrevention(subscriptions),
      Keys.IN_HOUSE_SOFTWARE -> inHouse
    )
  }

}

