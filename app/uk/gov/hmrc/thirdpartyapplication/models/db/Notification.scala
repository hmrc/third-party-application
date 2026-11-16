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

import java.time.Instant

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.services.SimpleEnumJsonFormatting

enum NotificationStatus {
  case SENT, FAILED
}

object NotificationStatus {
  def apply(text: String): Option[NotificationStatus] = NotificationStatus.values.find(_.toString().equalsIgnoreCase(text))

  def unsafeApply(text: String): NotificationStatus = apply(text).getOrElse(throw new RuntimeException(s"$text is not a valid Notification Status"))

  import play.api.libs.json.Format
  given Format[NotificationStatus] = SimpleEnumJsonFormatting.createStringFormatFor("Notification Status", apply)
}

enum NotificationType {
  case PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING
}

object NotificationType {
  def apply(text: String): Option[NotificationType] = NotificationType.values.find(_.toString().equalsIgnoreCase(text))

  def unsafeApply(text: String): NotificationType = apply(text).getOrElse(throw new RuntimeException(s"$text is not a valid Notification Type"))

  import play.api.libs.json.Format
  given Format[NotificationType] = SimpleEnumJsonFormatting.createStringFormatFor("Notification Type", apply)
}

case class Notification(applicationId: ApplicationId, lastUpdated: Instant, notificationType: NotificationType, status: NotificationStatus)
