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

package uk.gov.hmrc.apiplatform.modules.approvals.domain.services

import java.time.Instant

import play.api.libs.json.*

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationWithDetails
}

trait ResponsibleIndividualVerificationFrontendJsonFormatters extends EnvReads {

  import uk.gov.hmrc.play.json.Union

  given utcReads: Reads[Instant] = DefaultInstantReads

  given responsibleIndividualVerificationFormat: OFormat[ResponsibleIndividualToUVerification]                = Json.format[ResponsibleIndividualToUVerification]
  given responsibleIndividualTouUpliftVerificationFormat: OFormat[ResponsibleIndividualTouUpliftVerification] = Json.format[ResponsibleIndividualTouUpliftVerification]
  given responsibleIndividualUpdateVerificationFormat: OFormat[ResponsibleIndividualUpdateVerification]       = Json.format[ResponsibleIndividualUpdateVerification]

  given jsonFormatResponsibleIndividualVerification: OFormat[ResponsibleIndividualVerification] = Union.from[ResponsibleIndividualVerification]("verificationType")
    .and[ResponsibleIndividualToUVerification]("termsOfUse")
    .and[ResponsibleIndividualTouUpliftVerification]("termsOfUseUplift")
    .and[ResponsibleIndividualUpdateVerification]("adminUpdate")
    .format

  given responsibleIndividualVerificationWithDetailsFormat: OFormat[ResponsibleIndividualVerificationWithDetails] = Json.format[ResponsibleIndividualVerificationWithDetails]
}

object ResponsibleIndividualVerificationFrontendJsonFormatters extends ResponsibleIndividualVerificationFrontendJsonFormatters
