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

package uk.gov.hmrc.thirdpartyapplication.models

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.Token
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.{CollaboratorTestData, _}

class ApplicationSpec extends HmrcSpec with ApplicationStateUtil with UpliftRequestSamples with CollaboratorTestData {

  "Application with Uplift request" should {
    val app     =
      ApplicationData(
        ApplicationId.random,
        "MyApp",
        "myapp",
        Set.empty,
        None,
        "a",
        ApplicationTokens(Token(ClientId("cid"), "at")),
        productionState("user1"),
        Access.Standard(),
        now,
        Some(now)
      )
    val history = StateHistory(app.id, State.PENDING_GATEKEEPER_APPROVAL, Actors.AppCollaborator("1".toLaxEmail), changedAt = now)

    "create object" in {
      val result = ApplicationWithUpliftRequest.create(app, history)

      result shouldBe ApplicationWithUpliftRequest(app.id, app.name, history.changedAt, State.PRODUCTION)
    }

    "Fail to create when invalid state is sent" in {
      val ex = intercept[InconsistentDataState] {
        ApplicationWithUpliftRequest.create(app, history.copy(state = State.PENDING_REQUESTER_VERIFICATION))
      }

      ex.getMessage shouldBe "cannot create with invalid state: PENDING_REQUESTER_VERIFICATION"
    }
  }

  "Application from CreateApplicationRequest" should {
    def createRequestV2(access: StandardAccessDataToCopy, environment: Environment) = {
      ApplicationData.create(
        createApplicationRequest = CreateApplicationRequestV2(
          name = "an application",
          access = access,
          environment = environment,
          collaborators = Set("jim@example.com".admin()),
          upliftRequest = makeUpliftRequest(ApiIdentifier.random),
          requestedBy = "user@example.com",
          sandboxApplicationId = ApplicationId.random
        ),
        wso2ApplicationName = "wso2ApplicationName",
        environmentToken = Token(ClientId("clientId"), "accessToken"),
        createdOn = now
      )
    }

    def createRequestV1(access: Access, environment: Environment) = {
      ApplicationData.create(
        createApplicationRequest = CreateApplicationRequestV1(
          name = "an application",
          access = access,
          environment = environment,
          collaborators = Set("jim@example.com".admin()),
          subscriptions = None
        ),
        wso2ApplicationName = "wso2ApplicationName",
        environmentToken = Token(ClientId("clientId"), "accessToken"),
        createdOn = now
      )
    }

    "be automatically uplifted to PRODUCTION state when the app is for the sandbox environment" in {
      val actual = createRequestV1(Access.Standard(), Environment.SANDBOX)
      actual.state.name shouldBe State.PRODUCTION
    }

    "defer to STANDARD accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV2(StandardAccessDataToCopy(), Environment.PRODUCTION)
      actual.state.name shouldBe State.TESTING
    }

    "defer to PRIVILEGED accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV1(Access.Privileged(), Environment.PRODUCTION)
      actual.state.name shouldBe State.PRODUCTION
    }

    "defer to ROPC accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV1(Access.Ropc(), Environment.PRODUCTION)
      actual.state.name shouldBe State.PRODUCTION
    }

    "use the same value for createdOn and lastAccess fields" in {
      val actual = createRequestV1(Access.Standard(), Environment.PRODUCTION)
      actual.createdOn shouldBe actual.lastAccess.get
    }
  }

}
