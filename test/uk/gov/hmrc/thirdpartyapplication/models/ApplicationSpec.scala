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
import uk.gov.hmrc.apiplatform.modules.common.utils
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationName, ApplicationStateFixtures, State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.{
  CreateApplicationRequest,
  CreateApplicationRequestV1,
  CreateApplicationRequestV2,
  CreationAccess,
  StandardAccessDataToCopy
}
import uk.gov.hmrc.thirdpartyapplication.models.db.{StoredApplication, StoredToken}
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationSpec extends utils.HmrcSpec with ApplicationStateFixtures with UpliftRequestSamples with CollaboratorTestData with StoredApplicationFixtures
    with utils.FixedClock {

  "Application with Uplift request" should {
    val app     = storedApp
    val history = StateHistory(app.id, State.PENDING_GATEKEEPER_APPROVAL, Actors.AppCollaborator("1".toLaxEmail), changedAt = instant)

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

  def createRequestV1(access: CreationAccess, environment: Environment) = {
    val request: CreateApplicationRequest =
      CreateApplicationRequestV1(
        name = ApplicationName("an application"),
        access = access,
        description = None,
        environment = environment,
        collaborators = Set("jim@example.com".admin()),
        subscriptions = None
      )

    StoredApplication.create(
      createApplicationRequest = request,
      wso2ApplicationName = "wso2ApplicationName",
      environmentToken = StoredToken(ClientId("clientId"), "accessToken"),
      createdOn = instant
    )
  }

  "Application from CreateApplicationRequest" should {
    def createRequestV2(access: StandardAccessDataToCopy, environment: Environment) = {
      val request: CreateApplicationRequest =
        CreateApplicationRequestV2(
          name = ApplicationName("an application"),
          access = access,
          description = None,
          environment = environment,
          collaborators = Set("jim@example.com".admin()),
          upliftRequest = makeUpliftRequest(ApiIdentifier.random),
          requestedBy = "user@example.com",
          sandboxApplicationId = ApplicationId.random
        )

      StoredApplication.create(
        createApplicationRequest = request,
        wso2ApplicationName = "wso2ApplicationName",
        environmentToken = StoredToken(ClientId("clientId"), "accessToken"),
        createdOn = instant
      )
    }

    "be automatically uplifted to PRODUCTION state when the app is for the sandbox environment" in {
      val actual = createRequestV1(CreationAccess.Standard, Environment.SANDBOX)
      actual.state.name shouldBe State.PRODUCTION
    }

    "defer to STANDARD accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV2(StandardAccessDataToCopy(), Environment.PRODUCTION)
      actual.state.name shouldBe State.TESTING
    }

    "defer to PRIVILEGED accessType to determine application state when the app is for the production environment" in {
      val actual = createRequestV1(CreationAccess.Privileged, Environment.PRODUCTION)
      actual.state.name shouldBe State.PRODUCTION
    }

    "use the same value for createdOn and lastAccess fields" in {
      val actual = createRequestV1(CreationAccess.Standard, Environment.PRODUCTION)
      actual.createdOn shouldBe actual.lastAccess.get
    }
  }

}
