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
import uk.gov.hmrc.internalauth.client.test.BackendAuthComponentsStub
import uk.gov.hmrc.internalauth.client.test.StubBehaviour
import uk.gov.hmrc.internalauth.client.Retrieval
import play.api.test.StubControllerComponentsFactory
import play.api.mvc.ControllerComponents
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.config.AuthConfig
import play.api.http.Status.FORBIDDEN

class LdapGatekeeperRoleAuthorisationServiceSpec extends AsyncHmrcSpec with StubControllerComponentsFactory  {
  val fakeRequest = FakeRequest()
  
  val cc: ControllerComponents = stubControllerComponents()

  val expectedRetrieval = Retrieval.username ~ Retrieval.hasPredicate(LdapAuthorisationPredicate.gatekeeperReadPermission)

  trait Setup {
    val mockStubBehaviour = mock[StubBehaviour]
    val backendAuthComponents = BackendAuthComponentsStub(mockStubBehaviour)(cc, implicitly)
    
    def authConfig: AuthConfig
    lazy val underTest = new LdapGatekeeperRoleAuthorisationService(authConfig, backendAuthComponents)

    protected def stub(isAuth: Boolean) = when(mockStubBehaviour.stubAuth(None,expectedRetrieval)).thenReturn(Future.successful(uk.gov.hmrc.internalauth.client.~[Retrieval.Username, Boolean](Retrieval.Username("Bob"), isAuth)))
  }

  trait DisabledAuth {
    self: Setup =>
    val authConfig = AuthConfig(enabled = false, canDeleteApplications = false, authorisationKey = "Foo")
  }

  trait EnabledAuth {
    self: Setup =>
    val authConfig = AuthConfig(enabled = true, canDeleteApplications = false, authorisationKey = "Foo")
  }

  trait SessionPresent {
    self: Setup =>
    val request = fakeRequest.withSession("authToken" -> "Token some-token")
  }

  trait NoSessionPresent {
    self: Setup =>
    val request = fakeRequest
  }

  trait Authorised {
    self: Setup with SessionPresent =>

    stub(true)
  }

  trait Unauthorised {
    self: Setup with SessionPresent =>

    stub(false)
  }


  "with auth disabled" should {
    
    "return None (good result) when auth is not enabled" in new Setup with DisabledAuth with NoSessionPresent {
      val result = await(underTest.ensureHasGatekeeperRole(request))

      result shouldBe None
    }
  }

  "with auth enabled" should {
    "return None (good result) when user should be authorised " in new Setup with EnabledAuth with SessionPresent with Authorised {
      val result = await(underTest.ensureHasGatekeeperRole(request))

      result shouldBe None
    }

    "return Some(...) (forbidden) when user is present but not authorised" in new Setup with EnabledAuth with SessionPresent with Unauthorised {
       val result = await(underTest.ensureHasGatekeeperRole(request))

       result.value.header.status shouldBe FORBIDDEN
    }

    "return Some(...) (forbidden) when user is absent" in new Setup with EnabledAuth with NoSessionPresent {
       val result = await(underTest.ensureHasGatekeeperRole(request))

       result.value.header.status shouldBe FORBIDDEN
    }
  }
}
