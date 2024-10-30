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
import uk.gov.hmrc.thirdpartyapplication.domain.models.SubscriptionData
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationTokens, StoredApplication, StoredClientSecret, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.models.{StandardAccess => _}
import uk.gov.hmrc.thirdpartyapplication.util._

trait ApplicationRepositoryTestData extends StoredApplicationFixtures with CollaboratorTestData {

  lazy val defaultGrantLength = GrantLength.EIGHTEEN_MONTHS.period
  lazy val newGrantLength     = GrantLength.ONE_MONTH.period

  private def generateClientId = ClientId.random

  private def generateAccessToken = {
    val lengthOfRandomToken = 5
    nextString(lengthOfRandomToken)
  }

  def createAppWithStatusUpdatedOn(
      state: State,
      updatedOn: Instant = instant
    ): StoredApplication =
    anApplicationDataForTest(
      id = ApplicationId.random,
      prodClientId = generateClientId,
    ).withState(
      ApplicationState(
        state,
        Some("requestorEmail@example.com"),
        Some("requesterName"),
        Some("aVerificationCode"),
        updatedOn
      )
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
      clientSecrets: List[StoredClientSecret] = List(aClientSecret(hashedSecret = "hashed-secret")),
    ): StoredApplication = {

    // aNamedApplicationData(
    aNamedApplicationData(
      id,
      prodClientId,
      clientSecrets,
    )
  }

  def aNamedApplicationData(
      id: ApplicationId,
      prodClientId: ClientId = ClientId("aaa"),
      clientSecrets: List[StoredClientSecret] = List(aClientSecret(hashedSecret = "hashed-secret")),
      // refreshTokensAvailableFor: Period = defaultGrantLength,
    ): StoredApplication = {

    StoredApplication(
      id,
      ApplicationName(s"MyApp-$id"),
      s"MyApp-$id".toLowerCase(),
      Set("user@example.com".admin()),
      Some(CoreApplicationData.appDescription),
      "myapplication",
      ApplicationTokens(
        StoredToken(prodClientId, generateAccessToken, clientSecrets)
      ),
      appStateTesting,
      Access.Standard(),
      instant,
      Some(instant),
      refreshTokensAvailableFor = defaultGrantLength, //refreshTokensAvailableFor,
      checkInformation = None,
      allowAutoDelete = true
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
