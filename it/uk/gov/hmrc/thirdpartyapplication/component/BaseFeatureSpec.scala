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

package uk.gov.hmrc.thirdpartyapplication.component

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

import akka.stream.Materializer
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.scalatest._
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.play.guice.GuiceOneServerPerSuite

import play.api.test.RunningServer

import uk.gov.hmrc.thirdpartyapplication.MyTestServerFactory
import uk.gov.hmrc.thirdpartyapplication.component.stubs._

abstract class BaseFeatureSpec extends AnyFeatureSpec with GivenWhenThen with Matchers
    with BeforeAndAfterEach with BeforeAndAfterAll with GuiceOneServerPerSuite {

  override lazy val runningServer: RunningServer = MyTestServerFactory.start(app)

  lazy val serviceUrl = s"http://localhost:$port"
  val timeout         = 10 seconds

  def await[T](f: Future[T]): T = Await.result(f, timeout)

  val apiSubscriptionFieldsStub        = ApiSubscriptionFieldsStub
  val thirdPartyDelegatedAuthorityStub = ThirdPartyDelegatedAuthorityStub
  val authStub                         = AuthStub
  val totpStub                         = TOTPStub
  val awsApiGatewayStub                = AwsApiGatewayStub
  val emailStub                        = EmailStub
  val apiPlatformEventsStub            = ApiPlatformEventsStub

  val mocks = {
    Seq(apiSubscriptionFieldsStub, authStub, totpStub, thirdPartyDelegatedAuthorityStub, awsApiGatewayStub, emailStub, apiPlatformEventsStub)
  }

  implicit lazy val mat: Materializer    = app.materializer
  implicit lazy val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  override protected def beforeAll(): Unit = {
    mocks.foreach(m => if (!m.stub.server.isRunning) m.stub.server.start())
  }

  override protected def afterEach(): Unit = {
    mocks.foreach(_.stub.mock.resetMappings())
  }

  override protected def afterAll(): Unit = {
    mocks.foreach(_.stub.server.stop())
  }
}

case class MockHost(port: Int) {

  val server = new WireMockServer(
    WireMockConfiguration
      .wireMockConfig()
      .port(port)
  )

  val mock = new WireMock("localhost", port)
}

trait Stub {
  val stub: MockHost
}
