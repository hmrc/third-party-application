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
import cats.data.NonEmptySet
import uk.gov.hmrc.apiplatform.modules.common.services.NonEmptySetFormatters
sealed trait Access {
  val accessType: AccessType.Value
}

case class Standard(
    redirectUris: List[String] = List.empty,
    termsAndConditionsUrl: Option[String] = None,
    privacyPolicyUrl: Option[String] = None,
    organisationUrl: Option[String] = None,
    overrides: Set[OverrideFlag] = Set.empty,
    sellResellOrDistribute: Option[SellResellOrDistribute] = None,
    responsibleIndividual: Option[ResponsibleIndividual] = None,
    serverLocations: Option[NonEmptySet[String]] = None
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
  import NonEmptySetFormatters._

  import play.api.libs.functional.syntax._

  implicit val ordering = new cats.kernel.instances.StringOrder
  implicit val nesString = nesReads[String]

  val reads: Reads[Standard] = (
    (JsPath \ "redirectUris").read[List[String]] and
    (JsPath \ "termsAndConditionsUrl").readNullable[String] and
    (JsPath \ "privacyPolicyUrl").readNullable[String] and
    (JsPath \ "organisationUrl").readNullable[String] and
    (JsPath \ "overrides").read[Set[OverrideFlag]] and
    (JsPath \ "sellResellOrDistribute").readNullable[SellResellOrDistribute] and
    (JsPath \ "responsibleIndividual").readNullable[ResponsibleIndividual] and
    (JsPath \ "serverLocations").readNullable[NonEmptySet[String]]
  )(Standard.apply _)

  val writes: OWrites[Standard] = (
    (JsPath \ "redirectUris").write[List[String]] and
    (JsPath \ "termsAndConditionsUrl").writeNullable[String] and
    (JsPath \ "privacyPolicyUrl").writeNullable[String] and
    (JsPath \ "organisationUrl").writeNullable[String] and
    (JsPath \ "overrides").write[Set[OverrideFlag]] and
    (JsPath \ "sellResellOrDistribute").writeNullable[SellResellOrDistribute] and
    (JsPath \ "responsibleIndividual").writeNullable[ResponsibleIndividual] and
    (JsPath \ "serverLocations").writeNullable[NonEmptySet[String]]   
  )(unlift(Standard.unapply))

  implicit val format = OFormat(reads, writes)
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
