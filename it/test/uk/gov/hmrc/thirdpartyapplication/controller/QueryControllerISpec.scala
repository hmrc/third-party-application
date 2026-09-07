/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controller

import org.scalatest.BeforeAndAfterEach

import play.api.http._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.libs.ws._
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, ClientIdFixtures, UserIdFixtures}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaboratorsFixtures, State}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.Param.{MatchOneStateQP, PageNbrQP}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.{ApplicationQueries, ApplicationQuery}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.services.QueryParamsToQueryStringMap
import uk.gov.hmrc.thirdpartyapplication.controllers.query.QueryController
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, ApplicationRepositoryTestData}
import uk.gov.hmrc.thirdpartyapplication.util.WiremockSugar

class QueryControllerISpec extends ServerBaseISpec with WiremockSugar with BeforeAndAfterEach with ApplicationLogger with FixedClock with ClientIdFixtures with UserIdFixtures
    with ApplicationWithCollaboratorsFixtures with ApplicationRepositoryTestData {

  override protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.api-platform-events.port" -> stubPort
      )

  lazy val applicationRepository =
    app.injector.instanceOf[ApplicationRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(applicationRepository.ensureIndexes())
  }

  trait Setup {
    val inTest = app.injector.instanceOf[QueryController]

    val application1 = anApplicationDataForTest(
      ApplicationId.random,
      clientIdOne
    )
      .withState(appStatePendingGatekeeperApproval)

    val application2 = anApplicationDataForTest(
      ApplicationId.random,
      clientIdTwo
    )
      .withState(appStatePendingGatekeeperApproval)

    await(applicationRepository.save(application1))
    await(applicationRepository.save(application2))

    def callQuery(params: Map[String, String] = Map.empty, headers: Map[String, String] = Map.empty) = {
      val paramString                                           = params.toSeq.map { case (a, b) => s"$a=$b" } mkString ("?", "&", "")
      implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, s"/query${paramString}").withHeaders(headers.toSeq: _*)

      inTest.queryDispatcher()(request)
    }
  }

  val wsClient: WSClient = app.injector.instanceOf[WSClient]

  def asParams(qry: ApplicationQuery): Map[String, String] = {
    QueryParamsToQueryStringMap.toQuery(qry).map {
      case (k, vs) => k -> vs.mkString
    }
  }

  val singleQry    = asParams(ApplicationQueries.applicationByClientId(clientIdOne))
  val paginatedQry = asParams(ApplicationQuery.attemptToConstructQuery(List(PageNbrQP(1), MatchOneStateQP(State.PENDING_GATEKEEPER_APPROVAL))))
  val generalQry   = asParams(ApplicationQueries.applicationsByUserId(userIdOne))

  "QueryController:queryDispatcher" should {
    "parse a single query" in new Setup {
      val result = callQuery(singleQry)

      status(result) shouldBe OK
      contentType(result).value shouldBe ContentTypes.JSON
      contentAsJson(result) shouldBe Json.toJson(application1.asAppWithCollaborators)
    }

    "parse failing single query with stream" in new Setup {
      val result = callQuery(singleQry, Map(HeaderNames.ACCEPT -> "application/stream+json"))
      status(result) shouldBe BAD_REQUEST
    }

    "parse a general query" in new Setup {
      val result = callQuery(generalQry)

      status(result) shouldBe OK
      contentType(result).value shouldBe ContentTypes.JSON
      contentAsJson(result) shouldBe Json.toJson(List(application1.asAppWithCollaborators, application2.asAppWithCollaborators))
    }

    "parse a general query with streaming" in new Setup {
      val result = callQuery(generalQry, Map(HeaderNames.ACCEPT -> "application/stream+json"))

      status(result) shouldBe OK
      contentType(result).value shouldBe "application/stream+json"

      // When pulled with contentAsJson we only get the first record and it is not a JSON Array
      contentAsJson(result) shouldBe Json.toJson(application1.asAppWithCollaborators)
    }

    "parse a paginated query" in new Setup {
      val result = callQuery(paginatedQry)

      status(result) shouldBe OK
      contentType(result).value shouldBe ContentTypes.JSON

      val jsValue = contentAsJson(result)
      (jsValue \\ "applications").headOption.value shouldBe Json.toJson(List(application1.asAppWithCollaborators, application2.asAppWithCollaborators))
    }

    "parse failing paginated query with stream" in new Setup {
      val result = callQuery(paginatedQry, Map(HeaderNames.ACCEPT -> "application/stream+json"))

      status(result) shouldBe BAD_REQUEST
    }
  }
}
