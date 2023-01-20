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

import java.time.LocalDateTime

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationSpec extends HmrcSpec with ApplicationStateUtil with UpliftRequestSamples {

  "RateLimitTier" should {
    "have all rate limit tiers" in {
      import RateLimitTier._
      RateLimitTier.values shouldBe Set(RHODIUM, PLATINUM, GOLD, SILVER, BRONZE)
    }
  }

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
        Standard(),
        LocalDateTime.now,
        Some(LocalDateTime.now)
      )
    val history = StateHistory(app.id, State.PENDING_GATEKEEPER_APPROVAL, OldActor("1", ActorType.COLLABORATOR), changedAt = FixedClock.now)

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
          collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
          upliftRequest = makeUpliftRequest(ApiIdentifier.random),
          requestedBy = "user@example.com",
          sandboxApplicationId = ApplicationId.random
        ),
        wso2ApplicationName = "wso2ApplicationName",
        environmentToken = Token(ClientId("clientId"), "accessToken"),
        createdOn = FixedClock.now
      )
    }

    def createRequestV1(access: Access, environment: Environment) = {
      ApplicationData.create(
        createApplicationRequest = CreateApplicationRequestV1(
          name = "an application",
          access = access,
          environment = environment,
          collaborators = Set(Collaborator("jim@example.com", Role.ADMINISTRATOR, UserId.random)),
          subscriptions = None
        ),
        wso2ApplicationName = "wso2ApplicationName",
        environmentToken = Token(ClientId("clientId"), "accessToken"),
        createdOn = FixedClock.now
      )
    }

    "be automatically uplifted to PRODUCTION state when the app is for the sandbox environment" in {
      val actual = createRequestV1(Standard(), Environment.SANDBOX)
      actual.state.name shouldBe PRODUCTION
    }

    "defer to STANDARD accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV2(StandardAccessDataToCopy(), Environment.PRODUCTION)
      actual.state.name shouldBe TESTING
    }

    "defer to PRIVILEGED accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV1(Privileged(), Environment.PRODUCTION)
      actual.state.name shouldBe PRODUCTION
    }

    "defer to ROPC accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV1(Ropc(), Environment.PRODUCTION)
      actual.state.name shouldBe PRODUCTION
    }

    "use the same value for createdOn and lastAccess fields" in {
      val actual = createRequestV1(Standard(), Environment.PRODUCTION)
      actual.createdOn shouldBe actual.lastAccess.get
    }
  }

}
