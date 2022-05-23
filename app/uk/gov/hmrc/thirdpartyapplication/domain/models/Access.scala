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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import play.api.libs.json._

sealed trait Access {
  val accessType: AccessType.Value
}

case class ImportantSubmissionData(
  organisationUrl: Option[String] = None,
  responsibleIndividual: ResponsibleIndividual,
  serverLocations: Set[ServerLocation],
  termsAndConditionsLocation: TermsAndConditionsLocation,
  privacyPolicyLocation: PrivacyPolicyLocation,
  termsOfUseAcceptances: List[TermsOfUseAcceptance]
)

object ImportantSubmissionData {
  implicit val format = Json.format[ImportantSubmissionData]
}

case class Standard(
  redirectUris: List[String] = List.empty,
  termsAndConditionsUrl: Option[String] = None,
  privacyPolicyUrl: Option[String] = None,
  overrides: Set[OverrideFlag] = Set.empty,
  sellResellOrDistribute: Option[SellResellOrDistribute] = None,
  importantSubmissionData: Option[ImportantSubmissionData] = None
) extends Access {
  override val accessType = STANDARD
}

case class Privileged(
  totpIds: Option[TotpId] = None,
  scopes: Set[String] = Set.empty
) extends Access {
  override val accessType = PRIVILEGED
}

case class Ropc(scopes: Set[String] = Set.empty) extends Access {
  override val accessType = ROPC
}

object Standard {
  implicit val format = Json.format[Standard]
}

object Privileged {
  implicit val format = Json.format[Privileged]
}

object Ropc {
  implicit val format = Json.format[Ropc]
}

object Access {
  import uk.gov.hmrc.play.json.Union

  implicit val formatAccess = Union.from[Access]("accessType")
    .and[Standard](STANDARD.toString)
    .and[Privileged](PRIVILEGED.toString)
    .and[Ropc](ROPC.toString)
    .format
}
