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

import play.api.libs.json._
import uk.gov.hmrc.play.json.Union

sealed trait ServerLocation

object ServerLocation {
  case object InUk extends ServerLocation
  case object InEEA extends ServerLocation
  case object OutsideEEAWithAdequacy extends ServerLocation
  case object OutsideEEAWithoutAdequacy extends ServerLocation

  implicit val inUkFormat = Json.format[InUk.type]
  implicit val inEEAFormat = Json.format[InEEA.type]
  implicit val outsideEEAWithAdequacyFormat = Json.format[OutsideEEAWithAdequacy.type]
  implicit val outsideEEAWithoutAdequacyFormat = Json.format[OutsideEEAWithoutAdequacy.type]
  
  implicit val format = Union.from[ServerLocation]("serverLocation")
    .and[InUk.type]("inUk")
    .and[InEEA.type]("inEEA")
    .and[OutsideEEAWithAdequacy.type]("outsideEEAWithAdequacy")
    .and[OutsideEEAWithoutAdequacy.type]("outsideEEAWithoutAdequacy")
    .format
}