/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.controllers

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import play.api.test.Helpers._
import uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.UpliftLinkServiceMockModule
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import play.api.test.FakeRequest

class UpliftLinksControllerSpec extends AsyncHmrcSpec {

  trait Setup extends UpliftLinkServiceMockModule {
    val sandboxAppId = ApplicationId.random
    val prodAppId    = ApplicationId.random

    val controller = new UpliftLinksController(UpliftLinkServiceMock.aMock, stubControllerComponents())
  }

  "getSandboxAppIdForProductionApp" should {
    "return appId if link exists" in new Setup {
      UpliftLinkServiceMock.GetSandboxAppForProductionAppId.thenReturn(sandboxAppId)
      val result = controller.getSandboxAppIdForProductionApp(prodAppId)(FakeRequest(GET, "/"))
      status(result) shouldBe OK
    }

    "return 404 if link does not exist" in new Setup {
      UpliftLinkServiceMock.GetSandboxAppForProductionAppId.thenReturnNothing
      val result = controller.getSandboxAppIdForProductionApp(prodAppId)(FakeRequest(GET, "/"))
      status(result) shouldBe NOT_FOUND
    }
  }
}
