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

package uk.gov.hmrc.thirdpartyapplication.controllers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{apply => _}

import org.apache.http.HttpStatus._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.libs.json.{JsValue, Json, OWrites}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationStateFixtures
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, SubscriptionService}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

class CollaboratorControllerSpec extends ControllerSpec with ApplicationStateFixtures {

  import play.api.test.Helpers._

  implicit lazy val materializer: Materializer = NoMaterializer

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(X_REQUEST_ID_HEADER -> "requestId")

    val mockApplicationService  = mock[ApplicationService]
    val mockSubscriptionService = mock[SubscriptionService]

    val underTest = new CollaboratorController(
      mockApplicationService,
      mockSubscriptionService,
      Helpers.stubControllerComponents()
    )
  }

  "searchCollaborators" should {

    "succeed with a 200 (ok) when collaborators are found for an Api context and version" in new Setup {
      private val context                                      = "api1".asContext
      private val version                                      = "1.0".asVersion
      private val partialemail                                 = "partialemail"
      implicit val writes: OWrites[SearchCollaboratorsRequest] = Json.writes[SearchCollaboratorsRequest]
      implicit lazy val request: FakeRequest[JsValue]          = FakeRequest().withHeaders("X-name" -> "blob", "X-email-address" -> "test@example.com", "X-Server-Token" -> "abc123")
        .withBody(Json.toJson(SearchCollaboratorsRequest(context, version, Some(partialemail))))

      when(mockSubscriptionService.searchCollaborators(context, version, Some(partialemail))).thenReturn(Future.successful(List("user@example.com")))

      val result = underTest.searchCollaborators()(request)

      status(result) shouldBe SC_OK

      contentAsJson(result).as[Seq[String]] shouldBe Seq("user@example.com")
    }
  }
}
