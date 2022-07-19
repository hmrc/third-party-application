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

package uk.gov.hmrc.apiplatform.modules.gkauth.services

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import scala.concurrent.ExecutionContext.Implicits.global
import play.api.test.FakeRequest
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import play.api.http.Status.UNAUTHORIZED
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles

class StrideGatekeeperRoleAuthorisationServiceSpec extends AsyncHmrcSpec with StrideAuthConnectorMockModule  {
  val request = FakeRequest()
  
  trait Setup {
    def authControlConfig: AuthControlConfig

    val fakeStrideAuthRoles = StrideAuthRoles("A","B","C")

    lazy val underTest = new StrideGatekeeperRoleAuthorisationService(authControlConfig, fakeStrideAuthRoles, StrideAuthConnectorMock.aMock)
  }

  trait DisabledAuth {
    self: Setup =>
    val authControlConfig = AuthControlConfig(enabled = false, canDeleteApplications = false, authorisationKey = "Foo")
  }

  trait EnabledAuth {
    self: Setup =>
    val authControlConfig = AuthControlConfig(enabled = true, canDeleteApplications = false, authorisationKey = "Foo")
  }

  trait Authorised {
    self: Setup =>

    StrideAuthConnectorMock.Authorise.succeeds
  }

  trait Unauthorised {
    self: Setup =>

    StrideAuthConnectorMock.Authorise.fails
  }

  "with auth disabled" should {
    "return None (good result) when auth is not enabled" in new Setup with DisabledAuth with Unauthorised {
      val result = await(underTest.ensureHasGatekeeperRole(request))

      result shouldBe None
    }
  }

  "with auth enabled" should {
    "return None (good result) when user should be authorised " in new Setup with EnabledAuth with Authorised {
      val result = await(underTest.ensureHasGatekeeperRole(request))

      result shouldBe None
    }

    "return Some(...) (unauthorised) when user is present but not authorised" in new Setup with EnabledAuth with Unauthorised {
       val result = await(underTest.ensureHasGatekeeperRole(request))

       result.value.header.status shouldBe UNAUTHORIZED
    }
  }
}
