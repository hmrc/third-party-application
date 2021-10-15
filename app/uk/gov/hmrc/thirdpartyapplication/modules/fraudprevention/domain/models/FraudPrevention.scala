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

package uk.gov.hmrc.thirdpartyapplication.modules.fraudprevention.domain.models

object FraudPrevention {
  def contexts = Set(
    "vat-api",
    "business-details-api",
    "self-assessment-biss-api",
    "self-assessment-bsas-api",
    "cis-deductions-api",
    "individual-calculations-api",
    "individual-losses-api",
    "individuals-charges-api",
    "individuals-disclosures-api",
    "individuals-expenses-api",
    "individuals-income-received-api",
    "individuals-reliefs-api",
    "individuals-state-benefits-api",
    "obligations-api",
    "other-deductions-api",
    "self-assessment-api",
    "self-assessment-accounts-api"
  )
}
