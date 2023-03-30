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

package uk.gov.hmrc.thirdpartyapplication.component.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}
import play.api.http.Status.CREATED

object ApiPlatformEventsStub extends Stub {

  override val stub: MockHost = MockHost(16700)

  private val clientSecretAddedEventURL: String   = "/application-events/clientSecretAdded"
  private val clientSecretRemovedEventURL: String = "/application-events/clientSecretRemoved"
  private val apiSubscribedEventURL: String       = "/application-events/apiSubscribed"
  private val apiUnsubscribedEventURL: String     = "/application-events/apiUnsubscribed"
  private val applicationEventsURL: String        = "/application-event"

  def willReceiveEventType(eventType: String): Unit = {
    stub.mock.register(
      post(urlEqualTo(applicationEventsURL))
        .withRequestBody(containing(s""""eventType":"$eventType""""))
        .willReturn(
          aResponse()
            .withStatus(CREATED)
        )
    )
  }

  def verifyClientSecretAddedEventSent(): Unit = {
    verifyStubCalled(clientSecretAddedEventURL)
  }

  def verifyClientSecretRemovedEventSent(): Unit = {
    verifyStubCalled(clientSecretRemovedEventURL)
  }

  def verifyApiSubscribedEventSent(): Unit = {
    verifyStubCalled(apiSubscribedEventURL)
  }

  def verifyApiUnsubscribedEventSent(): Unit = {
    verifyStubCalled(apiUnsubscribedEventURL)
  }

  private def verifyStubCalled(urlString: String) = {
    stub.mock.verifyThat(postRequestedFor(urlEqualTo(urlString)))
  }

  def verifyApplicationEventPostBody(body: String) = {
    stubFor(
      post(urlEqualTo(applicationEventsURL))
        .withRequestBody(equalToJson(body))
        .willReturn(
          aResponse()
            .withStatus(CREATED)
        )
    )
  }

  def willReceiveClientSecretAddedEvent() = {
    stub.mock.register(post(urlEqualTo(clientSecretAddedEventURL))
      .willReturn(
        aResponse()
          .withStatus(CREATED)
      ))
  }

  def willReceiveClientRemovedEvent() = {
    stub.mock.register(post(urlEqualTo(clientSecretRemovedEventURL))
      .willReturn(
        aResponse()
          .withStatus(CREATED)
      ))
  }

  def willReceiveApiSubscribedEvent() = {
    stub.mock.register(post(urlEqualTo(apiSubscribedEventURL))
      .willReturn(
        aResponse()
          .withStatus(CREATED)
      ))
  }

  def willReceiveApiUnsubscribedEvent() = {
    stub.mock.register(post(urlEqualTo(apiUnsubscribedEventURL))
      .willReturn(
        aResponse()
          .withStatus(CREATED)
      ))
  }
}
