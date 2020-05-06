/*
 * Copyright 2020 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.controllers

import java.util.UUID

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.{Matchers, WordSpec}
import play.api.mvc.AnyContentAsEmpty
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.AccessController
import uk.gov.hmrc.thirdpartyapplication.services.{AccessService, ApplicationService}
import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NewAccessControllerSpec
  extends WordSpec
  with MockitoSugar
  with Matchers
//  with ArgumentMatchersSugar
//  with DefaultAwaitTimeout
{
  "do stuff" should {
//    val mockAuthConnector = mock[AuthConnector]
//    val mockApplicationService = mock[ApplicationService]
//    val mockAuthConfig = mock[AuthConfig]
//    val mockAccessService = mock[AccessService]
//    val fakeRequest: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
//
//    val applicationId = UUID.randomUUID()
//
//    val accessController =
//      new AccessController(mockAuthConnector, mockApplicationService, mockAuthConfig, mockAccessService, Helpers.stubControllerComponents())
//
//    val response = Future.failed(new RuntimeException("testing testing 123"))
//
//    when(mockAccessService.readOverrides(*)).thenReturn(response)
//
//    val result = accessController.readOverrides(applicationId)(fakeRequest)
//
//    import Helpers._
//
//    status(result) should be 500
  }
}