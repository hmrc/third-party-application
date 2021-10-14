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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services

import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifier
import uk.gov.hmrc.thirdpartyapplication.modules.fraudprevention.domain.models.FraudPrevention
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.Context

object DeriveContext {

  object Keys {
    val VAT_OR_ITSA = "VAT_OR_ITSA"
    val IN_HOUSE_SOFTWARE = "IN_HOUSE_SOFTWARE" // Stored on Application
  }

  def yesNoFromBoolean(b: Boolean) = if(b) "Yes" else "No"

  def deriveFraudPrevention(subscriptions: List[ApiIdentifier]): String =  {
    val appContexts = subscriptions.map(_.context.value).toSet
    yesNoFromBoolean(appContexts.intersect(FraudPrevention.contexts).nonEmpty)
  }

  def deriveFor(application: ApplicationData, subscriptions: List[ApiIdentifier]): Context = {
    import Keys._
    
    val resell = application.upliftData.fold("No")(ud => ud.sellResellOrDistribute.answer)
    val inHouse = if(resell == "Yes") "No" else "Yes"

    Map(
      VAT_OR_ITSA -> deriveFraudPrevention(subscriptions),
      IN_HOUSE_SOFTWARE -> inHouse
    )
  }

}
