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

package uk.gov.hmrc.thirdpartyapplication.connector

import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import uk.gov.hmrc.thirdpartyapplication.component.stubs.ApiPlatformEventsStub
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{
  ApiSubscribed,
  ApiUnsubscribed,
  ApplicationDeletedByGatekeeper,
  ClientSecretAddedV2,
  ClientSecretRemoved,
  CollaboratorActor,
  GatekeeperUserActor
}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, ClientId, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock
import uk.gov.hmrc.utils.ServerBaseISpec
import uk.gov.hmrc.thirdpartyapplication.util.WiremockSugar
import java.time.format.DateTimeFormatter

class ApiPlatformEventsConnectorISpec extends ServerBaseISpec with WiremockSugar {

  override protected def appBuilder: GuiceApplicationBuilder =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.api-platform-events.port" -> stubPort
      )

  trait Setup {
    val inTest      = app.injector.instanceOf[ApiPlatformEventsConnector]
    val appId       = ApplicationId.random
    val eventId     = UpdateApplicationEvent.Id.random
    val now         = FixedClock.now
    val nowAsString = now.format(DateTimeFormatter.ISO_DATE_TIME)
    val email       = "someemail@somewhere.com"
    val userName    = "bobby fingers"

    def testJson(updateApplicationEvent: UpdateApplicationEvent, expectedRequestBody: String) = {
      implicit val request = FakeRequest()

      ApiPlatformEventsStub.verifyApplicationEventPostBody(expectedRequestBody)
      await(inTest.sendApplicationEvent(updateApplicationEvent)) mustBe true
    }
  }

  "sendApplicationEvent" when {
    "sending event to api-platform-events" should {

      "send correct json for ApiSubscribed" in new Setup {

        val apiSubscribed = ApiSubscribed(eventId, appId, now, CollaboratorActor(email), "contextValue", "1.0")

        val expectedApiSubscribedRequestBody =
          s"""
             |{
             |"id" : "${eventId.value.toString}",
             |"applicationId" : "${appId.asText}",
             |"eventDateTime": "$nowAsString",
             |"actor" : {"email" :"$email",
             |"actorType": "COLLABORATOR"},
             |"context" :"contextValue",
             |"version" : "1.0",
             |"eventType" : "API_SUBSCRIBED_V2"
             |}""".stripMargin

        testJson(apiSubscribed, expectedApiSubscribedRequestBody)
      }

      "send correct json for ApiUnSubscribed" in new Setup {

        val apiUnSubscribed = ApiUnsubscribed(eventId, appId, now, CollaboratorActor(email), "contextValue", "1.0")

        val expectedApiUnSubscribedRequestBody =
          s"""
             |{
             |"id" : "${eventId.value.toString}",
             |"applicationId" : "${appId.asText}",
             |"eventDateTime": "$nowAsString",
             |"actor" : {"email" :"$email",
             |"actorType": "COLLABORATOR"},
             |"context" :"contextValue",
             |"version" : "1.0",
             |"eventType" : "API_UNSUBSCRIBED_V2"
             |}""".stripMargin

        testJson(apiUnSubscribed, expectedApiUnSubscribedRequestBody)
      }

      "send correct json for ClientSecretAddedV2" in new Setup {

        val clientSecretAddedV2 = ClientSecretAddedV2(eventId, appId, now, CollaboratorActor(email), "secretName", "secretValue")

        val expectedClientSecretAddedV2RequestBody =
          s"""
             |{
             |"id" : "${eventId.value.toString}",
             |"applicationId" : "${appId.asText}",
             |"eventDateTime": "$nowAsString",
             |"actor" : {"email" :"$email",
             |"actorType": "COLLABORATOR"},
             |"clientSecretId" :"secretName",
             |"clientSecretName" : "secretValue",
             |"eventType" : "CLIENT_SECRET_ADDED_V2"
             |}""".stripMargin

        testJson(clientSecretAddedV2, expectedClientSecretAddedV2RequestBody)
      }

      "send correct json for ClientSecretRemovedV2" in new Setup {

        val clientSecretAddedV2 = ClientSecretRemoved(eventId, appId, now, CollaboratorActor(email), "secretName", "secretValue")

        val expectedClientSecretAddedV2RequestBody =
          s"""
             |{
             |"id" : "${eventId.value.toString}",
             |"applicationId" : "${appId.asText}",
             |"eventDateTime": "$nowAsString",
             |"actor" : {"email" :"$email",
             |"actorType": "COLLABORATOR"},
             |"clientSecretId" :"secretName",
             |"clientSecretName" : "secretValue",
             |"eventType" : "CLIENT_SECRET_REMOVED_V2"
             |}""".stripMargin

        testJson(clientSecretAddedV2, expectedClientSecretAddedV2RequestBody)
      }

      "send correct json for ApplicationDeletedByGatekeeper" in new Setup {
        val clientId                       = ClientId.random
        val applicationDeletedByGatekeeper =
          ApplicationDeletedByGatekeeper(eventId, appId, now, GatekeeperUserActor(userName), clientId, "wso2ApplicationName", "Some reason", email)

        val applicationDeletedByGatekeeperRequestBody =
          s"""
             |{
             |"id" : "${eventId.value.toString}",
             |"applicationId" : "${appId.asText}",
             |"eventDateTime": "$nowAsString",
             |"actor" : {"user" :"$userName",
             |"actorType": "GATEKEEPER"},
             |"clientId" : "${clientId.value}",
             |"wso2ApplicationName" : "wso2ApplicationName",
             |"reasons" : "Some reason",
             |"requestingAdminEmail" : "someemail@somewhere.com",
             |"eventType" : "APPLICATION_DELETED_BY_GATEKEEPER"
             |}""".stripMargin

        testJson(applicationDeletedByGatekeeper, applicationDeletedByGatekeeperRequestBody)
      }

    }
  }

}
