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

package it.uk.gov.hmrc.thirdpartyapplication

import com.codahale.metrics.SharedMetricRegistries
import org.scalatest.concurrent.ScalaFutures
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import org.scalatest.{BeforeAndAfterEach, TestData}
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import play.api.{Application, Mode}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.controllers.DocumentationController

/**
  * Testcase to verify the capability of integration with the API platform.
  *
  * 1a, To expose API's to Third Party Developers, the service needs to make the API definition available under api/definition GET endpoint
  * 1b, The endpoints need to be defined in an application.raml file for all versions  For all of the endpoints defined documentation will be provided and
  * be available under api/documentation/[version]/[endpoint name] GET endpoint
  * Example: api/documentation/1.0/Fetch-Some-Data
  *
  * See: https://confluence.tools.tax.service.gov.uk/display/ApiPlatform/API+Platform+Architecture+with+Flows
  */
trait PlatformIntegrationSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar with ScalaFutures with BeforeAndAfterEach with GuiceOneAppPerTest {

  implicit def mat: akka.stream.Materializer = app.injector.instanceOf[akka.stream.Materializer]

  val publishApiDefinition: Boolean

  override def newAppForTest(testData: TestData): Application = GuiceApplicationBuilder()
    .configure("run.mode" -> "Stub")
    .configure(Map(
      "publishApiDefinition" -> publishApiDefinition,
      "api.context" -> "test-api-context"
    )).in(Mode.Test).build()

  trait Setup {
    SharedMetricRegistries.clear()
    val documentationController = app.injector.instanceOf[DocumentationController]
    val request = FakeRequest()
  }

}

class PublishApiDefinitionEnabledSpec extends PlatformIntegrationSpec {
  val publishApiDefinition = true

  "microservice" should {
    "return the JSON definition" in new Setup {
      val result = await(documentationController.definition()(request))
      status(result) shouldBe 200
      bodyOf(result) should include(""""context": "test-api-context"""")
    }

    "return the RAML" in new Setup {
      val result = await(documentationController.raml("1.0", "application.raml")(request))
      status(result) shouldBe 200
      bodyOf(result) should include("/test-api-context")
    }
  }
}

class PublishApiDefinitionDisabledSpec extends PlatformIntegrationSpec {
  val publishApiDefinition = false

  "microservice" should {

    "return a 404 from the definition endpoint" in new Setup {
      val result = await(documentationController.definition()(request))
      status(result) shouldBe 204
    }

    "return a 404 from the RAML endpoint" in new Setup {
      val result = await(documentationController.raml("1.0", "application.raml")(request))
      status(result) shouldBe 204
    }
  }
}
