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

package it.uk.gov.hmrc.thirdpartyapplication.component.stubs

import com.github.tomakehurst.wiremock.client.WireMock._
import it.uk.gov.hmrc.thirdpartyapplication.component.{MockHost, Stub}
import play.api.http.ContentTypes.FORM
import play.api.http.HeaderNames.{CONTENT_TYPE, COOKIE, SET_COOKIE}
import play.api.http.Status.OK
import play.api.libs.json._
import uk.gov.hmrc.thirdpartyapplication.models.APIIdentifier
import uk.gov.hmrc.thirdpartyapplication.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier._

object WSO2StoreStub extends Stub {

  val wso2KeyType = "PRODUCTION"
  override val stub = MockHost(22222)
  implicit val format1 = Json.format[WSO2Subscription]
  implicit val format2 = Json.format[WSO2SubscriptionResponse]

  def clientId(appName: String, env: Environment) = s"$appName-$env-key"

  def willAddUserSuccessfully(): Unit = {
    stub.mock.register(post(urlEqualTo("/store/site/blocks/user/sign-up/ajax/user-add.jag"))
      .willReturn(aResponse().withStatus(OK)
        .withBody( """{"error": false}""")))
  }

  def willLoginAndReturnCookieFor(username: String, password: String, cookie: String): Unit = {
    stub.mock.register(post(urlEqualTo("/store/site/blocks/user/login/ajax/login.jag"))
      .withHeader(CONTENT_TYPE, equalTo(FORM))
      .withRequestBody(equalTo(s"action=login&username=$username&password=$password"))
      .willReturn(aResponse().withStatus(OK)
        .withHeader(SET_COOKIE, cookie)
        .withBody( """{"error": false}""")))
  }

  def willLogout(cookie: String): Unit = {
    stub.mock.register(get(urlEqualTo("/store/site/blocks/user/login/ajax/login.jag?action=logout"))
      .withHeader(COOKIE, equalTo(cookie))
      .willReturn(aResponse().withStatus(OK)
        .withBody( """{"error": false}""")))
  }

  def willAddApplication(wso2ApplicationName: String): Unit = {
    stub.mock.register(post(urlEqualTo("/store/site/blocks/application/application-add/ajax/application-add.jag"))
      .withHeader(CONTENT_TYPE, equalTo(FORM))
      .withRequestBody(equalTo(s"action=addApplication&application=$wso2ApplicationName&tier=BRONZE_APPLICATION&description=&callbackUrl="))
      .willReturn(aResponse().withStatus(OK)
        .withBody( """{"error": false}""")))
  }

  def willFetchApplication(wso2ApplicationName: String, rateLimitTier: RateLimitTier): Unit = {
    val url = s"/store/site/blocks/application/application-list/ajax/application-list.jag"
    val uriParams = s"?action=getApplicationByName&applicationName=$wso2ApplicationName"

    stub.mock.register(get(urlEqualTo(url + uriParams))
      .withHeader(CONTENT_TYPE, equalTo(FORM))
      .willReturn(aResponse().withStatus(OK)
        .withBody( s"""{ "error" : false, "application" : { "tier" : "${rateLimitTier.toString}_APPLICATION" } }""")))
  }

  def willGenerateApplicationKey(appName: String, wso2ApplicationName: String): Unit = {
    stub.mock.register(post(urlEqualTo("/store/site/blocks/subscription/subscription-add/ajax/subscription-add.jag"))
      .withHeader(CONTENT_TYPE, equalTo(FORM))
      .withRequestBody(equalTo(
        s"action=generateApplicationKey&application=$wso2ApplicationName&keytype=$wso2KeyType&callbackUrl=&authorizedDomains=ALL&validityTime=-1"))
      .willReturn(aResponse().withStatus(OK)
        .withBody( s"""{"error":false,"data":{"key":{"consumerSecret":"secret","consumerKey":"$appName-key","accessToken":"token"}}}""")))
  }

  def willAddSubscription(wso2ApplicationName: String, context: String, version: String, rateLimitTier: RateLimitTier): Unit = {
    val uriParams = s"action=addAPISubscription&name=$context--$version&version=$version&provider=admin&tier=${rateLimitTier.toString}_SUBSCRIPTION&applicationName=$wso2ApplicationName"

    stub.mock.register(post(urlEqualTo("/store/site/blocks/subscription/subscription-add/ajax/subscription-add.jag"))
      .withHeader(CONTENT_TYPE, equalTo(FORM))
      .withRequestBody(equalTo(uriParams))
      .willReturn(aResponse().withStatus(OK)
        .withBody( """{"error": false}""")))
  }

  def willRemoveSubscription(wso2ApplicationName: String, context: String, version: String): Unit = {
    stub.mock.register(post(urlEqualTo("/store/site/blocks/subscription/subscription-remove/ajax/subscription-remove.jag"))
      .withHeader(CONTENT_TYPE, equalTo(FORM))
      .withRequestBody(equalTo(s"action=removeSubscription&name=$context--$version&version=$version&provider=admin&applicationName=$wso2ApplicationName"))
      .willReturn(aResponse().withStatus(OK)
        .withBody( """{"error": false}""")))
  }

  def willRemoveApplication(wso2ApplicationName: String): Unit = {
    stub.mock.register(post(urlEqualTo(s"/store/site/blocks/application/application-remove/ajax/application-remove.jag"))
      .withRequestBody(equalTo(s"action=removeApplication&application=$wso2ApplicationName"))
      .willReturn(aResponse().withStatus(OK)
        .withBody( """{"error": false}""")))
  }

  def willUpdateApplication(wso2ApplicationName: String, newRateLimitTier: RateLimitTier): Unit = {
    val uriParams = s"action=updateApplication&applicationOld=$wso2ApplicationName&applicationNew=$wso2ApplicationName" +
      s"&callbackUrlNew=&descriptionNew=&tier=${newRateLimitTier.toString}_APPLICATION"

    stub.mock.register(post(urlEqualTo(s"/store/site/blocks/application/application-update/ajax/application-update.jag"))
      .withRequestBody(equalTo(uriParams))
      .willReturn(aResponse().withStatus(OK)
        .withBody( """{"error": false}""")))
  }

  def willReturnApplicationSubscriptions(wso2ApplicationName: String, apis: Seq[APIIdentifier]): Unit = {
    val wso2Subscriptions: Seq[WSO2Subscription] = apis map { api => WSO2Subscription(s"${api.context}--${api.version}", api.version) }
    val wso2Response = Json.toJson(WSO2SubscriptionResponse(error = false, wso2Subscriptions)).toString

    stub.mock.register(post(urlEqualTo("/store/site/blocks/subscription/subscription-list/ajax/subscription-list.jag"))
      .withRequestBody(equalTo(s"action=getSubscriptionByApplication&app=$wso2ApplicationName"))
      .willReturn(aResponse().withStatus(OK)
        .withBody(wso2Response)))
  }

  def willReturnAllSubscriptions(appAndSubscriptions: (String, Seq[APIIdentifier])*): Unit = {
    val wso2Response: JsValue = JsObject(Seq(
      "error" -> JsBoolean(false),
      "subscriptions" -> JsObject(Seq(
        "applications" -> JsArray(appAndSubscriptions.map { case (name, apis) =>
          JsObject(Seq(
            "name" -> JsString(name),
            "subscriptions" -> JsArray(apis.map {
              api =>
                JsObject(Seq(
                  "name" -> JsString(s"${api.context}--${api.version}"),
                  "context" -> JsString(s"/${api.context}/${api.version}"),
                  "version" -> JsString(api.version)
                ))
            })
          ))
        })
      ))
    ))

    stub.mock.register(post(urlEqualTo("/store/site/blocks/subscription/subscription-list/ajax/subscription-list.jag"))
      .withRequestBody(equalTo(s"action=getAllSubscriptions"))
      .willReturn(aResponse().withStatus(OK)
        .withBody(wso2Response.toString())))
  }

  case class WSO2Subscription(apiName: String,
                              apiVersion: String,
                              apiProvider: String = "admin",
                              description: String = null,
                              subscribedTier: String = "Unlimited",
                              status: String = "PUBLISHED")

  case class WSO2SubscriptionResponse(error: Boolean, apis: Seq[WSO2Subscription])

}
