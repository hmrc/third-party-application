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

import play.api.http.Status.CREATED

import uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}

object ApiPlatformEventsStub extends Stub {

  override val stub: MockHost = MockHost(16700)

  private val applicationEventsURL: String = "/application-event"

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
    verifyStubCalledForEvent(applicationEventsURL, "CLIENT_SECRET_ADDED_V2")
  }

  def verifyClientSecretRemovedEventSent(): Unit = {
    verifyStubCalledForEvent(applicationEventsURL, "CLIENT_SECRET_REMOVED_V2")
  }

  def verifyApiSubscribedEventSent(): Unit = {
    verifyStubCalledForEvent(applicationEventsURL, "API_SUBSCRIBED_V2")
  }

  def verifyApiUnsubscribedEventSent(): Unit = {
    verifyStubCalledForEvent(applicationEventsURL, "API_UNSUBSCRIBED_V2")
  }

  def verifyGrantLengthChangedEventSent(): Unit = {
    verifyStubCalledForEvent(applicationEventsURL, "GRANT_LENGTH_CHANGED")
  }

  def verifyRatelLimitChangedEventSent(): Unit = {
    verifyStubCalledForEvent(applicationEventsURL, "RATE_LIMIT_CHANGED")
  }

  private def verifyStubCalledForEvent(urlString: String, eventType: String) = {
    stub.mock.verifyThat(postRequestedFor(urlEqualTo(urlString)).withRequestBody(containing(s""""eventType":"$eventType"""")))
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
    willReceiveEventType("CLIENT_SECRET_ADDED_V2")
  }

  def willReceiveClientRemovedEvent() = {
    willReceiveEventType("CLIENT_SECRET_REMOVED_V2")
  }

  def willReceiveApiSubscribedEvent() = {
    willReceiveEventType("API_SUBSCRIBED_V2")
  }

  def willReceiveApiUnsubscribedEvent() = {
    willReceiveEventType("API_UNSUBSCRIBED_V2")
  }

  def willReceiveChangeGrantLengthEvent() = {
    willReceiveEventType("GRANT_LENGTH_CHANGED")
  }

  def willReceiveChangeRateLimitEvent() = {
    willReceiveEventType("RATE_LIMIT_CHANGED")
  }
}
