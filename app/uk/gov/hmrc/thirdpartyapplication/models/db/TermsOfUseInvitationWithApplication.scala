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

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.{EMAIL_SENT, TermsOfUseInvitationState}

final case class TermsOfUseInvitationWithApplication(
    applicationId: ApplicationId,
    createdOn: Instant,
    lastUpdated: Instant,
    dueBy: Instant,
    reminderSent: Option[Instant] = None,
    status: TermsOfUseInvitationState = EMAIL_SENT,
    applications: Set[TermsOfUseApplication]
  ) {

    def getApplicationName(): String = {
      applications.head.name
    }
}

final case class TermsOfUseApplication(
    id: ApplicationId,
    name: String
)

object TermsOfUseInvitationWithApplication extends MongoJavatimeFormats.Implicits {
  implicit val formatTermsOfUseApplication: Format[TermsOfUseApplication] = Json.format[TermsOfUseApplication]
  implicit val formatTermsOfUseInvitationWithApplication: Format[TermsOfUseInvitationWithApplication] = Json.format[TermsOfUseInvitationWithApplication]
}
