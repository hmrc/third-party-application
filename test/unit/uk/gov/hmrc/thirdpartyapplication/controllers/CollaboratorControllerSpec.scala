/*
 * Copyright 2019 HM Revenue & Customs
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

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.apache.http.HttpStatus._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}
import uk.gov.hmrc.thirdpartyapplication.controllers._
import uk.gov.hmrc.thirdpartyapplication.services.SubscriptionService
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.Future
import scala.concurrent.Future.{apply => _}

class CollaboratorControllerSpec extends UnitSpec with ScalaFutures with MockitoSugar with WithFakeApplication with ApplicationStateUtil {

  implicit lazy val materializer = fakeApplication.materializer

  trait Setup {
    implicit val hc = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    implicit lazy val request = FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    val mockSubscriptionService = mock[SubscriptionService]

    val underTest = new CollaboratorController(
      mockSubscriptionService)
  }

  "searchCollaborators" should {

    "succeed with a 200 (ok) when collaborators are found for an Api context and version" in new Setup {
      val context="api1"
      val version="1.0"

      when(mockSubscriptionService.searchCollaborators(context, version)).thenReturn(Future.successful(Seq("user@example.com")))

      val result = await(underTest.searchCollaborators(context, version)(request))

      status(result) shouldBe SC_OK

      (jsonBodyOf(result)).as[Seq[String]] shouldBe Seq("user@example.com")
    }
  }
}