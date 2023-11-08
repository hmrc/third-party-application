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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApiIdentifier
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.fraudprevention.domain.models.FraudPrevention
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.AskWhen.Context
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.AskWhen.Context.Keys
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

object DeriveContext {

  def yesNoFromBoolean(b: Boolean) = if (b) "Yes" else "No"

  def deriveFraudPrevention(newUplift: String, subscriptions: List[ApiIdentifier]): String = {
    if ("Yes".equalsIgnoreCase(newUplift)) {
      // If a new terms of use uplift, then don't want fraud prevention questions
      "No"
    } else {
      val appContexts = subscriptions.map(_.context.value).toSet
      yesNoFromBoolean(appContexts.intersect(FraudPrevention.contexts).nonEmpty)
    }
  }

  def deriveFor(application: StoredApplication, subscriptions: List[ApiIdentifier]): Context = {

    val resell    = application.sellResellOrDistribute.fold("No")(s => s.answer)
    val inHouse   = if (resell == "Yes") "No" else "Yes"
    val newUplift = application.state.name match {
      case State.PRODUCTION => "Yes"
      case _                => "No"
    }

    Map(
      Keys.VAT_OR_ITSA             -> deriveFraudPrevention(newUplift, subscriptions),
      Keys.IN_HOUSE_SOFTWARE       -> inHouse,
      Keys.NEW_TERMS_OF_USE_UPLIFT -> newUplift
    )
  }
}
