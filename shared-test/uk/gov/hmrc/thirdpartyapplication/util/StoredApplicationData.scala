/*
 * Copyright 2024 HM Revenue & Customs
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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessData
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._

object StoredApplicationData extends FixedClock {

  def aSecret(secret: String): StoredClientSecret = StoredClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4), createdOn = instant)

  val serverToken           = "b3c83934c02df8b111e7f9f8700000"
  val serverTokenLastAccess = instant

  val productionToken = StoredToken(ClientId("aaa"), serverToken, List(aSecret("secret1"), aSecret("secret2")), Some(serverTokenLastAccess))

  val appName = ApplicationName("MyApp")

  val storedApp = StoredApplication(
    id = ApplicationIdData.one,
    name = appName,
    normalisedName = "myapp",
    collaborators = CollaboratorData.collaborators,
    description = Some(CoreApplicationData.appDescription),
    wso2ApplicationName = "aaaaaaaaaa",
    tokens = ApplicationTokens(productionToken),
    state = ApplicationStateData.production,
    access = AccessData.Standard.default,
    createdOn = instant,
    lastAccess = Some(instant),
    refreshTokensAvailableFor = GrantLength.EIGHTEEN_MONTHS.period,
    rateLimitTier = Some(RateLimitTier.BRONZE),
    environment = Environment.PRODUCTION,
    ipAllowlist = IpAllowListData.default
  )
}

trait StoredApplicationFixtures extends CoreApplicationFixtures with FixedClock {
  val productionToken       = StoredApplicationData.productionToken
  val serverToken           = StoredApplicationData.serverToken
  val serverTokenLastAccess = StoredApplicationData.serverTokenLastAccess

  val storedApp = StoredApplicationData.storedApp

  implicit class StoredApplicationFixtureSyntax(app: StoredApplication) {
    import monocle.syntax.all._
    def withId(anId: ApplicationId): StoredApplication       = app.focus(_.id).replace(anId)
    def withName(aName: ApplicationName): StoredApplication  = app.focus(_.name).replace(aName).focus(_.normalisedName).replace(aName.value.toLowerCase())
    def withEnvironment(env: Environment): StoredApplication = app.focus(_.environment).replace(env)
    def inSandbox(): StoredApplication                       = app.focus(_.environment).replace(Environment.SANDBOX)
    def inProduction(): StoredApplication                    = app.focus(_.environment).replace(Environment.PRODUCTION)

    def withCollaborators(collabs: Set[Collaborator]): StoredApplication = app.focus(_.collaborators).replace(collabs)
    def withCollaborators(collabs: Collaborator*): StoredApplication     = withCollaborators(collabs.toSet)
  }
}
