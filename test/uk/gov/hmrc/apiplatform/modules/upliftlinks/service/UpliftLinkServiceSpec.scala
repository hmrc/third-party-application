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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.service

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.repositories.UpliftLinksRepositoryMockModule
import uk.gov.hmrc.apiplatform.modules.upliftlinks.repositories.UpliftLinksRepository
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class UpliftLinkServiceSpec extends AsyncHmrcSpec {

  trait Setup extends UpliftLinksRepositoryMockModule {
    val upliftLinksRepository: UpliftLinksRepository = UpliftLinksRepositoryMock.aMock

    val sandboxApplicationId: ApplicationId    = ApplicationId.random
    val productionApplicationId: ApplicationId = ApplicationId.random

    val service = new UpliftLinkService(upliftLinksRepository)
  }

  "createUpliftLink" should {
    "add a new uplift link to the repo" in new Setup {
      val upliftLink: UpliftLink = UpliftLink(sandboxApplicationId, productionApplicationId)
      UpliftLinksRepositoryMock.Insert.thenReturn(upliftLink)

      val upliftLinkInserted: UpliftLink = await(service.createUpliftLink(sandboxApplicationId, productionApplicationId))

      upliftLinkInserted.sandboxApplicationId shouldBe sandboxApplicationId
      upliftLinkInserted.productionApplicationId shouldBe productionApplicationId

      UpliftLinksRepositoryMock.Insert.verifyCalled()
    }
  }

  "getSandboxAppForProductionAppId" should {
    "return correct sandbox appId if prod appId exists in repo" in new Setup {
      val upliftLink: UpliftLink = UpliftLink(sandboxApplicationId, productionApplicationId)
      UpliftLinksRepositoryMock.Find.thenReturn(upliftLink)

      val sandboxAppId: Option[ApplicationId] = await(service.getSandboxAppForProductionAppId(productionApplicationId).value)

      sandboxAppId shouldBe Some(upliftLink.sandboxApplicationId)
    }
    "return None if prod appId does not exist in repo" in new Setup {
      UpliftLinksRepositoryMock.Find.thenReturnNothing
      val sandboxAppId: Option[ApplicationId] = await(service.getSandboxAppForProductionAppId(productionApplicationId).value)

      sandboxAppId shouldBe None
    }
  }
}
