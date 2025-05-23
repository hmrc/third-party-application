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

package uk.gov.hmrc.apiplatform.modules.submissions.services

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.Inside

import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.fraudprevention.domain.models.FraudPrevention
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.AskWhen.Context.Keys
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.util._

class ContextServiceSpec
    extends AsyncHmrcSpec
    with Inside
    with SubmissionsTestData
    with StoredApplicationFixtures {

  trait Setup extends ApplicationRepositoryMockModule with SubscriptionRepositoryMockModule {

    val applicationData: StoredApplication = storedApp

    val underTest = new ContextService(ApplicationRepoMock.aMock, SubscriptionRepoMock.aMock)
  }

  "ContextService" should {
    "deriveContext when app is found" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      SubscriptionRepoMock.Fetch.thenReturn(FraudPrevention.contexts.head.asIdentifier, FraudPrevention.contexts.tail.head.asIdentifier)

      val result = await(underTest.deriveContext(applicationId).value)

      val expectedContext = Map(Keys.IN_HOUSE_SOFTWARE -> "Yes", Keys.VAT_OR_ITSA -> "No", Keys.NEW_TERMS_OF_USE_UPLIFT -> "Yes")

      result.value shouldBe expectedContext
    }
  }
}
