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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{Collaborator, RateLimitTier}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.apiplatform.modules.common.domain.models._

trait ApplicationTestData extends ApplicationStateUtil with CollaboratorTestData with ActorTestData with EmailTestData with FixedClock {

  def aSecret(secret: String): ClientSecretData = ClientSecretData(secret.takeRight(4), hashedSecret = secret.bcrypt(4), createdOn = now)

  val serverToken           = "b3c83934c02df8b111e7f9f8700000"
  val serverTokenLastAccess = now
  val productionToken       = Token(ClientId("aaa"), serverToken, List(aSecret("secret1"), aSecret("secret2")), Some(serverTokenLastAccess))

  val requestedByName  = "john smith"
  val requestedByEmail = "john.smith@example.com".toLaxEmail
  val grantLength      = 547

  def anApplicationData(
      applicationId: ApplicationId,
      state: ApplicationState = productionState(requestedByEmail.text),
      collaborators: Set[Collaborator] = Set(loggedInUserAdminCollaborator, otherAdminCollaborator, developerCollaborator),
      access: Access = Standard(),
      rateLimitTier: Option[RateLimitTier] = Some(RateLimitTier.BRONZE),
      environment: Environment = Environment.PRODUCTION,
      ipAllowlist: IpAllowlist = IpAllowlist(),
      grantLength: Int = grantLength
    ) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      state,
      access,
      now,
      Some(now),
      grantLength,
      rateLimitTier = rateLimitTier,
      environment = environment.toString,
      ipAllowlist = ipAllowlist
    )

  }

}
