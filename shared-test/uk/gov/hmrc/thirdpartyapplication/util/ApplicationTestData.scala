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

package uk.gov.hmrc.thirdpartyapplication.util

import com.github.t3hnar.bcrypt._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.models.db._

trait ApplicationTestData extends ApplicationStateUtil with CollaboratorTestData with FixedClock {

  def aSecret(secret: String): StoredClientSecret = StoredClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4), createdOn = instant)

  val serverToken           = "b3c83934c02df8b111e7f9f8700000"
  val serverTokenLastAccess = instant
  val productionToken       = StoredToken(ClientId("aaa"), serverToken, List(aSecret("secret1"), aSecret("secret2")), Some(serverTokenLastAccess))

  val requestedByName  = "john smith"
  val requestedByEmail = "john.smith@example.com".toLaxEmail
  val grantLength      = GrantLength.EIGHTEEN_MONTHS.period

  def anApplicationData(
      applicationId: ApplicationId = ApplicationIdData.one
    ): StoredApplication = {
    StoredApplication(
      applicationId,
      ApplicationName("MyApp"),
      "myapp",
      someCollaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      productionState(requestedByEmail.text),
      access = Access.Standard(),
      instant,
      Some(instant),
      grantLength,
      rateLimitTier = Some(RateLimitTier.BRONZE),
      environment = "PRODUCTION",
      ipAllowlist = IpAllowlist()
    )
  }

}
