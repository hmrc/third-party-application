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

package uk.gov.hmrc.thirdpartyapplication.domain.models

object OverrideType extends Enumeration {
  val PERSIST_LOGIN_AFTER_GRANT, GRANT_WITHOUT_TAXPAYER_CONSENT, SUPPRESS_IV_FOR_AGENTS, SUPPRESS_IV_FOR_ORGANISATIONS, SUPPRESS_IV_FOR_INDIVIDUALS = Value

  def typeOf(overrideFlag: OverrideFlag) = overrideFlag match {
    case PersistLogin()                   => OverrideType.PERSIST_LOGIN_AFTER_GRANT
    case SuppressIvForAgents(_)           => OverrideType.SUPPRESS_IV_FOR_AGENTS
    case SuppressIvForOrganisations(_)    => OverrideType.SUPPRESS_IV_FOR_ORGANISATIONS
    case SuppressIvForIndividuals(_)      => OverrideType.SUPPRESS_IV_FOR_INDIVIDUALS
    case GrantWithoutConsent(_)           => OverrideType.GRANT_WITHOUT_TAXPAYER_CONSENT
  }
}
