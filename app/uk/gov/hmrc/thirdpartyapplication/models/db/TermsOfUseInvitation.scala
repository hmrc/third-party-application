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
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit._

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.{TermsOfUseInvitationState, EMAIL_SENT}
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

final case class TermsOfUseInvitation(applicationId: ApplicationId, createdOn: Instant, lastUpdated: Instant, dueBy: Instant, reminderSent: Option[Instant] = None, status: TermsOfUseInvitationState = EMAIL_SENT)

object TermsOfUseInvitation extends MongoJavatimeFormats.Implicits {
  val daysUntilDue = 60

  implicit val format: Format[TermsOfUseInvitation] = Json.format[TermsOfUseInvitation]

  def apply(id: ApplicationId): TermsOfUseInvitation =
    TermsOfUseInvitation(
      id,
      Instant.now().truncatedTo(MILLIS),
      Instant.now().truncatedTo(MILLIS),
      Instant.now().truncatedTo(MILLIS).plus(daysUntilDue, ChronoUnit.DAYS),
      None,
      EMAIL_SENT
    )
}

