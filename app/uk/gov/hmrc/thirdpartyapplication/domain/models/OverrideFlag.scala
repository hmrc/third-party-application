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

import play.api.libs.json._

sealed trait OverrideFlag {
  lazy val overrideType: OverrideType.Value = OverrideType.typeOf(this)
}

case class PersistLogin() extends OverrideFlag
object PersistLogin {
  implicit val formatPersistLogin = Format[PersistLogin](
    Reads { _ => JsSuccess(PersistLogin()) },
    Writes { _ => Json.obj() })
}

case class SuppressIvForAgents(scopes: Set[String]) extends OverrideFlag
object SuppressIvForAgents {
  implicit val formatSuppressIvForAgents = Json.format[SuppressIvForAgents]
}

case class SuppressIvForOrganisations(scopes: Set[String]) extends OverrideFlag
object SuppressIvForOrganisations {
  implicit val formatSuppressIvForOrganisations = Json.format[SuppressIvForOrganisations]
}

case class GrantWithoutConsent(scopes: Set[String]) extends OverrideFlag
object GrantWithoutConsent {
  implicit val formatGrantWithoutConsent = Json.format[GrantWithoutConsent]
}

case class SuppressIvForIndividuals(scopes: Set[String]) extends OverrideFlag
object SuppressIvForIndividuals {
  implicit val formatSuppressIvForIndividuals = Json.format[SuppressIvForIndividuals]
}

object OverrideFlag {
  import uk.gov.hmrc.play.json.Union
  import OverrideType._
  
  implicit val formatOverride = Union.from[OverrideFlag]("overrideType")
  .and[GrantWithoutConsent](GRANT_WITHOUT_TAXPAYER_CONSENT.toString)
  .and[PersistLogin](PERSIST_LOGIN_AFTER_GRANT.toString)
  .and[SuppressIvForAgents](SUPPRESS_IV_FOR_AGENTS.toString)
  .and[SuppressIvForOrganisations](SUPPRESS_IV_FOR_ORGANISATIONS.toString)
  .and[SuppressIvForIndividuals](SUPPRESS_IV_FOR_INDIVIDUALS.toString)
  .format
}