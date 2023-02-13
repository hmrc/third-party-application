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

package uk.gov.hmrc.thirdpartyapplication.controllers

import java.util.UUID

import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator
import uk.gov.hmrc.thirdpartyapplication.util.CollaboratorTestData

trait ControllerTestData extends CollaboratorTestData {

  val collaborators: Set[Collaborator] = Set(
    "admin@example.com".admin(),
    "dev@example.com".developer()    
  )

  val standardAccess   = Standard(List("http://example.com/redirect"), Some("http://example.com/terms"), Some("http://example.com/privacy"))
  val privilegedAccess = Privileged(scopes = Set("scope1"))
  val ropcAccess       = Ropc()

  def anAPI() = {
    "some-context".asIdentifier("1.0")
  }

  def aSubcriptionData() = {
    SubscriptionData(anAPI(), Set(ApplicationId.random, ApplicationId.random))
  }

  def anAPIJson() = {
    """{ "context" : "some-context", "version" : "1.0" }"""
  }

  def aNewApplicationResponse(
      access: Access = standardAccess,
      environment: Environment = Environment.PRODUCTION,
      appId: ApplicationId = ApplicationId.random,
      state: ApplicationState = ApplicationState(State.TESTING)
    ) = {
    val grantLengthInDays = 547
    new ApplicationResponse(
      appId,
      ClientId("clientId"),
      "gatewayId",
      "My Application",
      environment.toString,
      Some("Description"),
      collaborators,
      FixedClock.now,
      Some(FixedClock.now),
      grantLengthInDays,
      None,
      standardAccess.redirectUris,
      standardAccess.termsAndConditionsUrl,
      standardAccess.privacyPolicyUrl,
      access,
      state
    )
  }

  def aNewExtendedApplicationResponse(access: Access, environment: Environment = Environment.PRODUCTION, subscriptions: List[ApiIdentifier] = List.empty) =
    extendedApplicationResponseFromApplicationResponse(aNewApplicationResponse(access, environment)).copy(subscriptions = subscriptions)

  def extendedApplicationResponseFromApplicationResponse(app: ApplicationResponse) = {
    new ExtendedApplicationResponse(
      app.id,
      app.clientId,
      app.gatewayId,
      app.name,
      app.deployedTo,
      app.description,
      app.collaborators,
      app.createdOn,
      app.lastAccess,
      app.grantLength,
      app.redirectUris,
      app.termsAndConditionsUrl,
      app.privacyPolicyUrl,
      app.access,
      app.state,
      app.rateLimitTier,
      app.checkInformation,
      app.blocked,
      app.trusted,
      UUID.randomUUID().toString,
      List.empty
    )
  }
}
