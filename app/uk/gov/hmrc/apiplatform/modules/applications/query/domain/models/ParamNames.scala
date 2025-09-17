/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.applications.query.domain.models

object ParamNames {

  val WantSubscriptions = "wantSubscriptions"

  val ServerToken   = "serverToken"
  val ClientId      = "clientId"
  val ApplicationId = "applicationId"

  val PageNbr  = "pageNbr"
  val PageSize = "pageSize"

  val Sort = "sort"

  val NoSubscriptions  = "noSubscriptions"
  val HasSubscriptions = "oneOrMoreSubscriptions"
  val ApiContext       = "context"
  val ApiVersionNbr    = "versionNbr"

  val LastUsedAfter  = "lastUsedAfter"
  val LastUsedBefore = "lastUsedBefore"

  val UserId = "userId"

  val Environment = "environment"

  val IncludeDeleted = "includeDeleted"

  val DeleteRestriction = "deleteRestriction"

  val Status           = "status"
  val StatusDateBefore = "statusDate"

  val Search           = "search"
  val Name             = "name"
  val VerificationCode = "verificationCode"

  val AccessType = "accessType"

}
