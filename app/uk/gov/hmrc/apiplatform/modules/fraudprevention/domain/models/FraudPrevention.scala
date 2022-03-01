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

package uk.gov.hmrc.apiplatform.modules.fraudprevention.domain.models

object FraudPrevention {
  def contexts = Set(
    "organisations/vat",
    "individuals/business/details",
    "individuals/self-assessment/income-summary",
    "individuals/self-assessment/adjustable-summary",
    "individuals/deductions/cis",
    "individuals/calculations",
    "individuals/losses",
    "individuals/charges",
    "individuals/disclosures",
    "individuals/expenses",
    "individuals/income-received",
    "individuals/reliefs",
    "individuals/state-benefits",
    "obligations/details",
    "individuals/deductions/other",
    "self-assessment",
    "accounts/self-assessment"
  )
}
