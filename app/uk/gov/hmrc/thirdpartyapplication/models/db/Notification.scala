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

package uk.gov.hmrc.thirdpartyapplication.models.db

import uk.gov.hmrc.thirdpartyapplication.domain.utils.EnumJson
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import play.api.libs.json.{Format, Json, OFormat}
import java.time.LocalDateTime

object NotificationStatus extends Enumeration {
  type NotificationStatus = Value
  val SENT, FAILED = Value

  implicit val formatNotificationStatus = EnumJson.enumFormat(NotificationStatus)
}

object NotificationType extends Enumeration {
  type NotificationType = Value
  val PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING = Value

  implicit val formatNotificationType = EnumJson.enumFormat(NotificationType)
}

import NotificationStatus._
import NotificationType._

case class Notification(applicationId: ApplicationId, lastUpdated: LocalDateTime, notificationType: NotificationType, status: NotificationStatus)

object Notification {
  implicit val dateFormat: Format[LocalDateTime]         = MongoJavatimeFormats.localDateTimeFormat
  implicit val formatNotification: OFormat[Notification] = Json.format[Notification]
}
