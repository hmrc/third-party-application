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

package uk.gov.hmrc.thirdpartyapplication.repository

import java.time.{Instant, Period}
import scala.util.Random.nextString

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.SubscriptionData
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredClientSecret, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.models.{StandardAccess => _}
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData

trait ApplicationRepositoryTestData extends ApplicationTestData with ApplicationStateUtil {

  lazy val defaultGrantLength = GrantLength.EIGHTEEN_MONTHS.period
  lazy val newGrantLength     = GrantLength.ONE_MONTH.period

  private def generateClientId = ClientId.random

  private def generateAccessToken = {
    val lengthOfRandomToken = 5
    nextString(lengthOfRandomToken)
  }

  def createAppWithStatusUpdatedOn(
      state: State,
      updatedOn: Instant,
      allowAutoDelete: Boolean = true
    ): StoredApplication =
    anApplicationDataForTest(
      id = ApplicationId.random,
      prodClientId = generateClientId,
      state = ApplicationState(
        state,
        Some("requestorEmail@example.com"),
        Some("requesterName"),
        Some("aVerificationCode"),
        updatedOn
      ),
      allowAutoDelete = allowAutoDelete
    )

  def aSubscriptionData(
      context: String,
      version: String,
      applicationIds: ApplicationId*
    ) = {
    SubscriptionData(context.asIdentifier(version), Set(applicationIds: _*))
  }

  def anApplicationDataForTest(
      id: ApplicationId,
      prodClientId: ClientId = ClientId("aaa"),
      state: ApplicationState = testingState(),
      access: Access = Access.Standard(),
      refreshTokensAvailableFor: Period = defaultGrantLength,
      users: Set[Collaborator] = Set(
        "user@example.com".admin()
      ),
      checkInformation: Option[CheckInformation] = None,
      clientSecrets: List[StoredClientSecret] = List(aClientSecret(hashedSecret = "hashed-secret")),
      allowAutoDelete: Boolean = true
    ): StoredApplication = {

    aNamedApplicationData(
      id,
      s"myApp-${id.value}",
      prodClientId,
      state,
      access,
      users,
      checkInformation,
      clientSecrets,
      refreshTokensAvailableFor,
      allowAutoDelete
    )
  }

  def aNamedApplicationData(
      id: ApplicationId,
      name: String,
      prodClientId: ClientId = ClientId("aaa"),
      state: ApplicationState = testingState(),
      access: Access = Access.Standard(),
      users: Set[Collaborator] = Set("user@example.com".admin()),
      checkInformation: Option[CheckInformation] = None,
      clientSecrets: List[StoredClientSecret] = List(aClientSecret(hashedSecret = "hashed-secret")),
      refreshTokensAvailableFor: Period = defaultGrantLength,
      allowAutoDelete: Boolean = true
    ): StoredApplication = {

    StoredApplication(
      id,
      name,
      name.toLowerCase,
      users,
      Some("description"),
      "myapplication",
      ApplicationTokens(
        StoredToken(prodClientId, generateAccessToken, clientSecrets)
      ),
      state,
      access,
      instant,
      Some(instant),
      refreshTokensAvailableFor = refreshTokensAvailableFor,
      checkInformation = checkInformation,
      allowAutoDelete = allowAutoDelete
    )
  }

  def aClientSecret(id: ClientSecret.Id = ClientSecret.Id.random, name: String = "", lastAccess: Option[Instant] = None, hashedSecret: String = "") =
    StoredClientSecret(
      id = id,
      name = name,
      lastAccess = lastAccess,
      hashedSecret = hashedSecret,
      createdOn = instant
    )

}
