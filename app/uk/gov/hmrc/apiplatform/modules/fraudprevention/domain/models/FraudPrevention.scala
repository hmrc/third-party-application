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

package uk.gov.hmrc.apiplatform.modules.fraudprevention.domain.models

object FraudPrevention {

  def contexts = Set(
    "accounts/self-assessment",
    "individuals/business/details",
    "individuals/business/end-of-period-statement",
    "individuals/business/property",
    "individuals/business/self-employment",
    "individuals/calculations",
    "individuals/charges",
    "individuals/deductions/cis",
    "individuals/deductions/other",
    "individuals/disclosures",
    "individuals/expenses",
    "individuals/income-received",
    "individuals/losses",
    "individuals/reliefs",
    "individuals/self-assessment/adjustable-summary",
    "individuals/self-assessment/income-summary",
    "individuals/state-benefits",
    "obligations/details",
    "organisations/insolvent/vat",
    "organisations/vat",
    "self-assessment"
  )
}
