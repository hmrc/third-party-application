/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.common.controllers.binders

import java.util.UUID

import uk.gov.hmrc.apiplatform.modules.common.domain.models.*
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId

// Workaround for Play unable to path bind opaque types

object RouteModels {
  case class SimpleApplicationId(value: UUID) extends AnyVal
  case class SimpleUserId(value: UUID)        extends AnyVal
  case class SimpleSubmissionId(value: UUID)  extends AnyVal

  type SimpleClientId      = String
  type SimpleApiVersionNbr = String
  type SimpleApiContext    = String

  object Conversions {

    given Conversion[SimpleApplicationId, ApplicationId] with
      def apply(x: SimpleApplicationId): ApplicationId = ApplicationId(x.value)

    given Conversion[SimpleUserId, UserId] with
      def apply(x: SimpleUserId): UserId = UserId(x.value)

    given Conversion[SimpleSubmissionId, SubmissionId] with
      def apply(x: SimpleSubmissionId): SubmissionId = SubmissionId.unsafeApply(x.value.toString)

    given Conversion[Option[SimpleUserId], Option[UserId]] with
      def apply(ox: Option[SimpleUserId]): Option[UserId] = ox.map(x => UserId.apply(x.value))

    given Conversion[SimpleApiVersionNbr, ApiVersionNbr] with
      def apply(x: SimpleApiVersionNbr): ApiVersionNbr = ApiVersionNbr(x)

    given Conversion[SimpleApiContext, ApiContext] with
      def apply(x: SimpleApiContext): ApiContext = ApiContext(x)

  }
}
