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

import play.api.libs.json.{Format, Json, OFormat}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{INITIAL, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.ResponsibleIndividual
import uk.gov.hmrc.play.json.Union
import java.time.{LocalDateTime, ZoneOffset}

sealed trait ResponsibleIndividualVerification {
  def id: ResponsibleIndividualVerificationId
  def applicationId: ApplicationId
  def submissionId: Submission.Id
  def submissionInstance: Int
  def applicationName: String
  def createdOn: LocalDateTime
  def state: ResponsibleIndividualVerificationState
}

object ResponsibleIndividualVerification {
  implicit val dateFormat: Format[LocalDateTime]                                            = MongoJavatimeFormats.localDateTimeFormat
  implicit val riVerificationFormat: OFormat[ResponsibleIndividualToUVerification]          = Json.format[ResponsibleIndividualToUVerification]
  implicit val riUpdateVerificationFormat: OFormat[ResponsibleIndividualUpdateVerification] = Json.format[ResponsibleIndividualUpdateVerification]

  implicit val jsonFormatResponsibleIndividualVerification = Union.from[ResponsibleIndividualVerification]("verificationType")
    .and[ResponsibleIndividualToUVerification]("termsOfUse")
    .and[ResponsibleIndividualUpdateVerification]("adminUpdate")
    .format
}

case class ResponsibleIndividualToUVerification(
    id: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random,
    applicationId: ApplicationId,
    submissionId: Submission.Id,
    submissionInstance: Int,
    applicationName: String,
    createdOn: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    state: ResponsibleIndividualVerificationState = INITIAL
  ) extends ResponsibleIndividualVerification

case class ResponsibleIndividualUpdateVerification(
    id: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random,
    applicationId: ApplicationId,
    submissionId: Submission.Id,
    submissionInstance: Int,
    applicationName: String,
    createdOn: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    state: ResponsibleIndividualVerificationState = INITIAL,
    responsibleIndividual: ResponsibleIndividual
  ) extends ResponsibleIndividualVerification
