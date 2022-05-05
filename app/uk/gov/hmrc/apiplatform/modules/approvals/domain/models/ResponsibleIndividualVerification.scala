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

package uk.gov.hmrc.apiplatform.modules.approvals.domain.models

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.repository.MongoJavaTimeFormats

import java.time.LocalDateTime

object ResponsibleIndividualVerification {
  import play.api.libs.json.Json

  implicit val dateFormat = MongoJavaTimeFormats.localDateTimeFormat
  implicit val format = Json.format[ResponsibleIndividualVerification]
}

case class ResponsibleIndividualVerification(
    id: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random,
    applicationId: ApplicationId,
    submissionId: Submission.Id,
    submissionInstance: Int,
    applicationName: String,
    createdOn: LocalDateTime = LocalDateTime.now()
)


