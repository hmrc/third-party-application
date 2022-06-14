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

import play.api.libs.json.Json
import uk.gov.hmrc.play.json.Union
import java.time.LocalDateTime

trait ApplicationUpdate {
  def instigator: UserId
  def timestamp: LocalDateTime
}

trait GatekeeperApplicationUpdate extends ApplicationUpdate {
  def gatekeeperUser: String
}

case class ChangeProductionApplicationName(instigator: UserId, timestamp: LocalDateTime, gatekeeperUser: String, newName: String) extends GatekeeperApplicationUpdate

trait ApplicationUpdateFormatters {
  implicit val changeNameFormatter = Json.format[ChangeProductionApplicationName]
  implicit val applicationUpdateRequestFormatter = Union.from[ApplicationUpdate]("updateType")
    .and[ChangeProductionApplicationName]("changeProductionApplicationName")
    .format
}