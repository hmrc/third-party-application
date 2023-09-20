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

package uk.gov.hmrc.apiplatform.modules.approvals.domain.models

import java.time.{LocalDateTime, ZoneOffset}

import play.api.libs.json.{Format, Json, OFormat}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.play.json.Union

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{INITIAL, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.thirdpartyapplication.domain.models.ResponsibleIndividual

sealed trait ResponsibleIndividualVerification {
  def id: ResponsibleIndividualVerificationId
  def applicationId: ApplicationId
  def submissionId: SubmissionId
  def submissionInstance: Int
  def applicationName: String
  def createdOn: LocalDateTime
  def state: ResponsibleIndividualVerificationState
}

object ResponsibleIndividualVerification {
  implicit val dateFormat: Format[LocalDateTime]                                                  = MongoJavatimeFormats.localDateTimeFormat
  implicit val riVerificationFormat: OFormat[ResponsibleIndividualToUVerification]                = Json.format[ResponsibleIndividualToUVerification]
  implicit val riVerificationTouUpliftFormat: OFormat[ResponsibleIndividualTouUpliftVerification] = Json.format[ResponsibleIndividualTouUpliftVerification]
  implicit val riUpdateVerificationFormat: OFormat[ResponsibleIndividualUpdateVerification]       = Json.format[ResponsibleIndividualUpdateVerification]

  val VerificationTypeToU: String       = "termsOfUse"
  val VerificationTypeTouUplift: String = "termsOfUseUplift"
  val VerificationTypeUpdate: String    = "adminUpdate"

  implicit val jsonFormatResponsibleIndividualVerification = Union.from[ResponsibleIndividualVerification]("verificationType")
    .and[ResponsibleIndividualToUVerification](VerificationTypeToU)
    .and[ResponsibleIndividualTouUpliftVerification](VerificationTypeTouUplift)
    .and[ResponsibleIndividualUpdateVerification](VerificationTypeUpdate)
    .format
}

case class ResponsibleIndividualToUVerification(
    id: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random,
    applicationId: ApplicationId,
    submissionId: SubmissionId,
    submissionInstance: Int,
    applicationName: String,
    createdOn: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    state: ResponsibleIndividualVerificationState = INITIAL
  ) extends ResponsibleIndividualVerification

case class ResponsibleIndividualTouUpliftVerification(
    id: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random,
    applicationId: ApplicationId,
    submissionId: SubmissionId,
    submissionInstance: Int,
    applicationName: String,
    createdOn: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    requestingAdminName: String,
    requestingAdminEmail: LaxEmailAddress,
    state: ResponsibleIndividualVerificationState = INITIAL
  ) extends ResponsibleIndividualVerification

case class ResponsibleIndividualUpdateVerification(
    id: ResponsibleIndividualVerificationId = ResponsibleIndividualVerificationId.random,
    applicationId: ApplicationId,
    submissionId: SubmissionId,
    submissionInstance: Int,
    applicationName: String,
    createdOn: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    responsibleIndividual: ResponsibleIndividual,
    requestingAdminName: String,
    requestingAdminEmail: LaxEmailAddress,
    state: ResponsibleIndividualVerificationState = INITIAL
  ) extends ResponsibleIndividualVerification
