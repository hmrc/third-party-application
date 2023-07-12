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

package uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class UpdateDeleteAllowedAttributeServiceSpec extends AsyncHmrcSpec {

  trait Setup extends ApplicationRepositoryMockModule {
    val underTest                                          = new UpdateDeleteAllowedAttributeService(ApplicationRepoMock.aMock)

  }

  "updateDeleteAllowedAttribute" should {
    "update allowAutoDelete flag to true" in new Setup {
      ApplicationRepoMock.UpdateAllApplicationsWithDeleteAllowed.succeeds(1)

      ApplicationRepoMock.UpdateAllApplicationsWithDeleteAllowed.verifyCalled()
    }

  }
}