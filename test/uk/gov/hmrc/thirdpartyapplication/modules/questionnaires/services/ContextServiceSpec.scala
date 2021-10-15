/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.services

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import org.scalatest.Inside
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import  scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.modules.fraudprevention.domain.models.FraudPrevention
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.DeriveContext

class ContextServiceSpec 
    extends AsyncHmrcSpec 
    with Inside 
    with SubmissionsTestData 
    with ApplicationTestData {
  
  trait Setup extends ApplicationRepositoryMockModule with SubscriptionRepositoryMockModule {

    val applicationId: ApplicationId = ApplicationId.random
    val applicationData: ApplicationData = anApplicationData(applicationId)

    val underTest = new ContextService(ApplicationRepoMock.aMock, SubscriptionRepoMock.aMock)
  }
  
  "ContextService" should {
    "deriveContext when app is found" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      SubscriptionRepoMock.Fetch.thenReturn(FraudPrevention.contexts.head.asIdentifier, FraudPrevention.contexts.tail.head.asIdentifier)

      val result = await(underTest.deriveContext(applicationId).value)

      val expectedContext = Map(DeriveContext.Keys.IN_HOUSE_SOFTWARE -> "Yes", DeriveContext.Keys.VAT_OR_ITSA -> "Yes")
      
      result.right.value shouldBe expectedContext
    }
  }
}
