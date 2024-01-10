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

package uk.gov.hmrc.apiplatform.modules.gkauth.services

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import play.api.http.HeaderNames.AUTHORIZATION
import play.api.http.Status.UNAUTHORIZED
import play.api.mvc.ControllerComponents
import play.api.test.{FakeRequest, StubControllerComponentsFactory}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.internalauth.client.Retrieval
import uk.gov.hmrc.internalauth.client.test.{BackendAuthComponentsStub, StubBehaviour}
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class LdapGatekeeperRoleAuthorisationServiceSpec extends AsyncHmrcSpec with StubControllerComponentsFactory {
  val fakeRequest = FakeRequest()

  val cc: ControllerComponents = stubControllerComponents()

  val expectedRetrieval = Retrieval.username ~ Retrieval.hasPredicate(LdapAuthorisationPredicate.gatekeeperReadPermission)

  trait Setup {
    val mockStubBehaviour     = mock[StubBehaviour]
    val backendAuthComponents = BackendAuthComponentsStub(mockStubBehaviour)(cc, implicitly)

    def authControlConfig: AuthControlConfig
    lazy val underTest = new LdapGatekeeperRoleAuthorisationService(authControlConfig, backendAuthComponents)

    protected def stub(
        isAuth: Boolean
      ) = when(mockStubBehaviour.stubAuth(None, expectedRetrieval)).thenReturn(Future.successful(uk.gov.hmrc.internalauth.client.~[Retrieval.Username, Boolean](
      Retrieval.Username("Bob"),
      isAuth
    )))
  }

  trait DisabledAuth {
    self: Setup =>
    val authControlConfig = AuthControlConfig(enabled = false, canDeleteApplications = false, authorisationKey = "Foo")
  }

  trait EnabledAuth {
    self: Setup =>
    val authControlConfig = AuthControlConfig(enabled = true, canDeleteApplications = false, authorisationKey = "Foo")
  }

  trait AuthHeaderPresent {
    self: Setup =>
    val request     = fakeRequest.withHeaders((AUTHORIZATION, "xxx")) // .withSession("authToken" -> "Token some-token")
    implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
  }

  trait NoAuthHeaderPresent {
    self: Setup =>
    val request     = fakeRequest
    implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
  }

  trait Authorised {
    self: Setup with AuthHeaderPresent =>

    stub(true)
  }

  trait Unauthorised {
    self: Setup with AuthHeaderPresent =>

    stub(false)
  }

  "with auth disabled" should {
    "return None (good result) when auth is not enabled" in new Setup with DisabledAuth with NoAuthHeaderPresent {
      val result = await(underTest.ensureHasGatekeeperRole())

      result shouldBe None
    }
  }

  "with auth enabled" should {
    "return None (good result) when user should be authorised " in new Setup with EnabledAuth with AuthHeaderPresent with Authorised {
      val result = await(underTest.ensureHasGatekeeperRole())

      result shouldBe None
    }

    "return Some(...) (unauthorized) when user is present but not authorised" in new Setup with EnabledAuth with AuthHeaderPresent with Unauthorised {
      val result = await(underTest.ensureHasGatekeeperRole())

      result.value.header.status shouldBe UNAUTHORIZED
    }

    "return Some(...) (unauthorized) when user is absent" in new Setup with EnabledAuth with NoAuthHeaderPresent {
      val result = await(underTest.ensureHasGatekeeperRole())

      result.value.header.status shouldBe UNAUTHORIZED
    }
  }
}
