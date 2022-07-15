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
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.GatekeeperRoles
import play.api.test.{FakeRequest, StubMessagesFactory}
import play.api.mvc.MessagesRequest
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.LoggedInRequest
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles
import play.api.mvc.Result
import play.api.http.Status._
import org.scalatest.prop.TableDrivenPropertyChecks
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector

class StrideAuthorisationServiceSpec extends AsyncHmrcSpec with StrideAuthConnectorMockModule with StubMessagesFactory with TableDrivenPropertyChecks {
  val strideAuthRoles = StrideAuthRoles(adminRole = "test-admin", superUserRole = "test-superUser", userRole = "test-user")
  val fakeRequest = FakeRequest()
  val msgRequest = new MessagesRequest(fakeRequest, stubMessagesApi())
  
  trait Setup {
    val strideAuthConfig = StrideAuthConnector.Config(strideAuthBaseUrl = "")
    
    val underTest = new StrideAuthorisationService(
      strideAuthConnector = StrideAuthConnectorMock.aMock,
      strideAuthRoles
    )
  }

  "createStrideRefiner" should {
     "return the appropriate results" in new Setup {
      import GatekeeperRoles._
      
      val cases = Table( 
        ( "requiredRole", "user has role",  "expected outcome"),
        ( ADMIN,          ADMIN,            Right(ADMIN)),
        ( SUPERUSER,      ADMIN,            Right(ADMIN)),
        ( USER,           ADMIN,            Right(ADMIN)),
        ( ADMIN,          SUPERUSER,        Left(FORBIDDEN)),
        ( SUPERUSER,      SUPERUSER,        Right(SUPERUSER)),
        ( USER,           SUPERUSER,        Right(SUPERUSER)),
        ( ADMIN,          USER,             Left(FORBIDDEN)),
        ( SUPERUSER,      USER,             Left(FORBIDDEN)),
        ( USER,           USER,             Right(USER))
      )

      forAll(cases) { case (requiredRole, userIsOfRole, expected) =>
        StrideAuthConnectorMock.Authorise.returnsFor(userIsOfRole)

        val result: Either[Result, LoggedInRequest[_]] = await(underTest.createStrideRefiner(requiredRole)(msgRequest))
        expected match {
          case Right(role) => result.right.value.role shouldBe role
          case Left(statusCode) => result.left.value.header.status shouldBe statusCode
        }
      }
    }

    "return a Forbidden when there is no active session" in new Setup {
      StrideAuthConnectorMock.Authorise.failsWithNoActiveSession

      val result: Either[Result, LoggedInRequest[_]] = await(underTest.createStrideRefiner(GatekeeperRoles.USER)(msgRequest))

      result.left.value.header.status shouldBe FORBIDDEN
    }
  }
}
