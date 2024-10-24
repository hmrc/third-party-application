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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.CollaboratorTestData

trait ControllerTestData extends ApplicationWithCollaboratorsFixtures with CollaboratorTestData with FixedClock {

  val collaborators: Set[Collaborator] = standardApp.collaborators

  def anAPI() = {
    "some-context".asIdentifier("1.0")
  }

  def aSubcriptionData() = {
    SubscriptionData(anAPI(), Set(ApplicationId.random, ApplicationId.random))
  }

  def anAPIJson() = {
    """{ "context" : "some-context", "version" : "1.0" }"""
  }

  // def aNewApplicationResponse(
  //     access: Access = standardAccess,
  //     environment: Environment = Environment.PRODUCTION,
  //     appId: ApplicationId = ApplicationId.random,
  //     state: ApplicationState = ApplicationState(State.TESTING, updatedOn = instant)
  //   ): ApplicationWithCollaborators = {
  //   ApplicationWithCollaboratorsData.standardApp
  //   // new Application(
  //   //   appId,
  //   //   ClientId("clientId"),
  //   //   "gatewayId",
  //   //   ApplicationName("My Application"),
  //   //   environment.toString,
  //   //   Some("Description"),
  //   //   collaborators,
  //   //   instant,
  //   //   Some(instant),
  //   //   grantLength,
  //   //   None,
  //   //   standardAccess.redirectUris,
  //   //   standardAccess.termsAndConditionsUrl,
  //   //   standardAccess.privacyPolicyUrl,
  //   //   access,
  //   //   state
  //   // )
  // }

  // def aNewExtendedApplicationResponse(access: Access, environment: Environment = Environment.PRODUCTION, subscriptions: List[ApiIdentifier] = List.empty)
  //     : ApplicationWithSubscriptions =
  //   aNewApplicationResponse(access, environment).withSubscriptions(subscriptions.toSet)

  // def extendedApplicationResponseFromApplicationResponse(app: Application) = {
  //   new ExtendedApplicationResponse(
  //     app.id,
  //     app.clientId,
  //     app.gatewayId,
  //     app.name,
  //     app.deployedTo,
  //     app.description,
  //     app.collaborators,
  //     app.createdOn,
  //     app.lastAccess,
  //     app.grantLength,
  //     app.redirectUris,
  //     app.termsAndConditionsUrl,
  //     app.privacyPolicyUrl,
  //     app.access,
  //     app.state,
  //     app.rateLimitTier,
  //     app.checkInformation,
  //     app.blocked,
  //     UUID.randomUUID().toString,
  //     List.empty,
  //     allowAutoDelete = app.moreApplication.allowAutoDelete
  //   )
  // }
}
