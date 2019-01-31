/*
 * Copyright 2019 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.models

import java.util.UUID

import uk.gov.hmrc.thirdpartyapplication.models.State.{PRODUCTION, TESTING}
import uk.gov.hmrc.thirdpartyapplication.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.play.test.UnitSpec
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil

class ApplicationSpec extends UnitSpec with ApplicationStateUtil {

  "RateLimitTier" should {

    "have all rate limit tiers" in {

      import RateLimitTier._
      RateLimitTier.values.toSet shouldBe Set(PLATINUM, GOLD, SILVER, BRONZE)
    }
  }

  "Wso2Api" should {

    "construct a WSO2 name from the context and version" in {

      Wso2Api.create(APIIdentifier("some/context", "1.0")) shouldBe Wso2Api("some--context--1.0", "1.0")

    }

  }

  "API" should {

    "deconstruct the context from a WSO2 api name" in {

      APIIdentifier.create(Wso2Api("some--context--1.0", "1.0")) shouldBe APIIdentifier("some/context", "1.0")

    }

  }

  "Application with Uplift request" should {
    val app = ApplicationData(UUID.randomUUID(), "MyApp", "myapp",
      Set.empty, None,
      "a", "a", "a",
      ApplicationTokens(EnvironmentToken("cid", "cs", "at"), EnvironmentToken("cid", "cs", "at")), productionState("user1"),
      Standard(Seq.empty, None, None))
    val history = StateHistory(app.id, State.PENDING_GATEKEEPER_APPROVAL, Actor("1", ActorType.COLLABORATOR))

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
    def createRequest(access: Access, environment: Environment) = {
      ApplicationData.create(
        application = CreateApplicationRequest(
          name = "an application",
          access = access,
          environment = environment,
          collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR))),
        wso2Username = "wso2Username",
        wso2Password = "wso2Password",
        wso2ApplicationName = "wso2ApplicationName",
        tokens = ApplicationTokens(
          production = EnvironmentToken("p-clientId", "p-clientSecret", "p-accessToken"),
          sandbox = EnvironmentToken("s-clientId", "s-clientSecret", "s-accessToken")))
    }

    "be automatically uplifted to PRODUCTION state when the app is for the sandbox environment" in {
      val actual = createRequest(Standard(), Environment.SANDBOX)
      actual.state.name shouldBe PRODUCTION
    }

    "defer to STANDARD accessType to determine application state when the app is for the production environment" in {
      val actual = createRequest(Standard(), Environment.PRODUCTION)
      actual.state.name shouldBe TESTING
    }

    "defer to PRIVILEGED accessType to determine application state when the app is for the production environment" in {
      val actual = createRequest(Privileged(), Environment.PRODUCTION)
      actual.state.name shouldBe PRODUCTION
    }

    "defer to ROPC accessType to determine application state when the app is for the production environment" in {
      val actual = createRequest(Ropc(), Environment.PRODUCTION)
      actual.state.name shouldBe PRODUCTION
    }
  }


}
