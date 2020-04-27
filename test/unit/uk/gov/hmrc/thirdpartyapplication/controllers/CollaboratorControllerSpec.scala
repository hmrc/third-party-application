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

import akka.stream.Materializer
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.apache.http.HttpStatus._
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.controllers._
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, SubscriptionService}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{apply => _}

class CollaboratorControllerSpec extends ControllerSpec with ApplicationStateUtil {

  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = fakeApplication().materializer

  trait Setup {
    implicit val hc = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")
    implicit lazy val request = FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")

    val mockSubscriptionService = mock[SubscriptionService]
    val mockApplicationService = mock[ApplicationService]

    val underTest = new CollaboratorController(mockSubscriptionService, mockApplicationService)
  }

  "searchCollaborators" should {

    "succeed with a 200 (ok) when collaborators are found for an Api context and version" in new Setup {
      private val context="api1"
      private val version="1.0"
      private val partialemail = "partialemail"

      when(mockSubscriptionService.searchCollaborators(context, version, Some(partialemail))).thenReturn(Future.successful(List("user@example.com")))

      val result = underTest.searchCollaborators(context, version, Some(partialemail))(request)

      status(result) shouldBe SC_OK

      contentAsJson(result).as[Seq[String]] shouldBe Seq("user@example.com")
    }
  }

  "findAllUniqueCollaborators" should {
    "succeed with a 200 (ok) and return the collaborators" in new Setup {
      when(mockApplicationService.findAllUniqueCollaborators).thenReturn(Future.successful(Set("user@example.com")))

      val result = underTest.findAllUniqueCollaborators(request)

      status(result) shouldBe SC_OK
      contentAsJson(result).as[Set[String]] shouldBe Set("user@example.com")
    }
  }
}
