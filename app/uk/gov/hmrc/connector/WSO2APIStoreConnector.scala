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

package uk.gov.hmrc.connector

import com.google.common.base.Charsets
import javax.inject.Inject
import play.api.Logger
import play.api.http.ContentTypes.FORM
import play.api.http.HeaderNames.{CONTENT_TYPE, COOKIE, SET_COOKIE}
import play.api.http.Status.OK
import play.api.libs.json._
import play.utils.UriEncoding
import uk.gov.hmrc.config.WSHttp
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.models.RateLimitTier._
import uk.gov.hmrc.models._
import uk.gov.hmrc.scheduled.Retrying

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class WSO2APIStoreConnector @Inject() extends HttpConnector {

  val http = WSHttp
  val serviceUrl = s"${baseUrl("wso2-store")}/store/site/blocks"
  val adminUsername: String = getConfString("wso2-store.username", "admin")

  def login(username: String, password: String)(implicit hc: HeaderCarrier): Future[String] = {
    Logger.debug(s"User logging in: [$username]")
    val url = s"$serviceUrl/user/login/ajax/login.jag"
    val encodedPassword = UriEncoding.encodePathSegment(password, Charsets.UTF_8.name())
    val payload =
      s"""action=login
         |&username=$username
         |&password=$encodedPassword
         |""".stripMargin.replaceAll("\n", "")

    post(url, payload, headers()).map { response =>
      response.allHeaders(SET_COOKIE) mkString ";"
    }
  }

  def logout(cookie: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Logger.debug("User logging out")
    val url = s"$serviceUrl/user/login/ajax/login.jag?action=logout"

    get(url, headers(cookie)) map toHasSucceeded
  }

  def createUser(username: String, password: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Logger.debug(s"Creating user $username")
    val url = s"$serviceUrl/user/sign-up/ajax/user-add.jag"
    val payload =
      s"""action=addUser
         |&username=$username
         |&password=$password
         |&allFieldsValues=firstname|lastname|email
         |""".stripMargin.replaceAll("\n", "")

    post(url, payload, headers()) map toHasSucceeded
  }

  def createApplication(cookie: String, wso2ApplicationName: String)
                       (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Logger.debug(s"Creating application [$wso2ApplicationName]")
    val url = s"$serviceUrl/application/application-add/ajax/application-add.jag"
    val payload =
      s"""action=addApplication
         |&application=$wso2ApplicationName
         |&tier=BRONZE_APPLICATION
         |&description=
         |&callbackUrl=
         |""".stripMargin.replaceAll("\n", "")

    post(url, payload, headers(cookie)) map toHasSucceeded
  }

  def updateApplication(cookie: String, wso2ApplicationName: String, rateLimitTier: RateLimitTier)
                       (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val wso2RateLimitTier: String = s"${rateLimitTier.toString.toUpperCase}_APPLICATION"
    Logger.debug(s"Updating application [$wso2ApplicationName] - Setting rate limit tier to [$wso2RateLimitTier]")
    val url = s"$serviceUrl/application/application-update/ajax/application-update.jag"
    val payload =
      s"""action=updateApplication
         |&applicationOld=$wso2ApplicationName
         |&applicationNew=$wso2ApplicationName
         |&callbackUrlNew=
         |&descriptionNew=
         |&tier=$wso2RateLimitTier
         |""".stripMargin.replaceAll("\n", "")

    post(url, payload, headers(cookie)) map toHasSucceeded
  }

  def deleteApplication(cookie: String, wso2ApplicationName: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Logger.debug(s"Deleting application [$wso2ApplicationName]")
    val url = s"$serviceUrl/application/application-remove/ajax/application-remove.jag"
    val payload = s"action=removeApplication&application=$wso2ApplicationName"

    post(url, payload, headers(cookie)) map toHasSucceeded
  }

  def generateApplicationKey(cookie: String, wso2ApplicationName: String, environment: Environment.Value)
                            (implicit hc: HeaderCarrier): Future[EnvironmentToken] = {
    Logger.debug(s"Generating $environment keys for $wso2ApplicationName")
    val url = s"$serviceUrl/subscription/subscription-add/ajax/subscription-add.jag"
    val payload =
      s"""action=generateApplicationKey
         |&application=$wso2ApplicationName
         |&keytype=$environment
         |&callbackUrl=
         |&authorizedDomains=ALL
         |&validityTime=-1
         |""".stripMargin.replaceAll("\n", "")

    post(url, payload, headers(cookie)).map { result =>
      extractKeys(result.json) { keys =>
        EnvironmentToken(keys.consumerKey, keys.consumerSecret, keys.accessToken)
      }
    }
  }

  private def extractKeys[T](js: JsValue)(f: Keys => T): T = {
    Try((js \ "error").as[Boolean]) match {
      case Failure(e) => throw new RuntimeException(e.getMessage, e)
      case Success(true) => throw new RuntimeException((js \ "message").as[String])
      case _ => f((js \ "data" \ "key").as[Keys])
    }
  }

  private def parseRateLimitTier(tier: String): Option[RateLimitTier] = {
    Try(RateLimitTier.withName(tier.replaceFirst("_APPLICATION", ""))).toOption
  }

  private def extractRateLimitTier(js: JsValue): RateLimitTier = {
    Try((js \ "error").as[Boolean]) match {
      case Failure(e) => throw new RuntimeException(e.getMessage, e)
      case Success(true) => throw new RuntimeException((js \ "message").as[String])
      case _ =>
        val tier = (js \ "application" \ "tier").as[String]
        parseRateLimitTier(tier).getOrElse(throw new RuntimeException(s"Invalid rate limit tier: $tier"))
    }
  }

  private def getApplication(cookie: String, wso2ApplicationName: String)(implicit hc: HeaderCarrier) = {
    Logger.debug(s"Fetching application [$wso2ApplicationName]")
    val url = s"$serviceUrl/application/application-list/ajax/application-list.jag"
    val uriParams = s"action=getApplicationByName&applicationName=$wso2ApplicationName"

    get(s"$url?$uriParams", headers(cookie))
  }

  def getApplicationRateLimitTier(cookie: String, wso2ApplicationName: String)
                                 (implicit hc: HeaderCarrier): Future[RateLimitTier] = {
    getApplication(cookie, wso2ApplicationName) map { response => extractRateLimitTier(response.json) }
  }

  def addSubscription(cookie: String, wso2ApplicationName: String, api: WSO2API, rateLimitTier: Option[RateLimitTier], retryMax: Int)
                     (implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    def normalise(rateLimitTier: Option[RateLimitTier]) = {
      s"${rateLimitTier.getOrElse(RateLimitTier.BRONZE).toString.toUpperCase}_SUBSCRIPTION"
    }

    // NOTE:
    //  WSO2 Store throws `org.wso2.carbon.apimgt.api.APIManagementException` if you try to add a subscription that already exists
    //  In WSO2 Store carbon log file you see `org.wso2.carbon.apimgt.api.SubscriptionAlreadyExistingException`

    Logger.debug(s"Application: [$wso2ApplicationName] is subscribing to [${api.name}-${api.version}]")
    val url = s"$serviceUrl/subscription/subscription-add/ajax/subscription-add.jag"
    val payload =
      s"""action=addAPISubscription
         |&name=${api.name}
         |&version=${api.version}
         |&provider=$adminUsername
         |&tier=${normalise(rateLimitTier)}
         |&applicationName=$wso2ApplicationName
         |""".stripMargin.replaceAll("\n", "")

    def subscribe() = {
      post(url, payload, headers(cookie)) map toHasSucceeded
    }

    Retrying.retry(subscribe(), 180.milliseconds, retryMax)
  }

  def removeSubscription(cookie: String, wso2ApplicationName: String, api: WSO2API, retryMax: Int)
                        (implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    // NOTE: WSO2's removeSubscription API is idempotent - if requested to remove a subscription that doesn't exist, it will respond with no error

    Logger.debug(s"Application: [$wso2ApplicationName] is unsubscribing from [${api.name}-${api.version}]")
    val url = s"$serviceUrl/subscription/subscription-remove/ajax/subscription-remove.jag"
    val payload =
      s"""action=removeSubscription
         |&name=${api.name}
         |&version=${api.version}
         |&provider=$adminUsername
         |&applicationName=$wso2ApplicationName
         |""".stripMargin.replaceAll("\n", "")

    def unsubscribe() = {
      post(url, payload, headers(cookie)) map toHasSucceeded
    }

    Retrying.retry(unsubscribe(), 180.milliseconds, retryMax)
  }

  def getSubscriptions(cookie: String, wso2ApplicationName: String)
                      (implicit hc: HeaderCarrier): Future[Seq[WSO2API]] = {
    Logger.debug(s"Fetching subscriptions for application: [$wso2ApplicationName]")
    val url = s"$serviceUrl/subscription/subscription-list/ajax/subscription-list.jag"
    val payload = s"action=getSubscriptionByApplication&app=$wso2ApplicationName"

    post(url, payload, headers(cookie)).map { response =>
      (response.json \ "apis").as[Seq[JsValue]].map { apiJson =>
        WSO2API(
          (apiJson \ "apiName").as[String],
          (apiJson \ "apiVersion").as[String]
        )
      }
    }
  }

  def getAllSubscriptions(cookie: String)(implicit hc: HeaderCarrier): Future[Map[String, Seq[WSO2API]]] = {
    Logger.debug("Fetching subscriptions for all applications")
    val url = s"$serviceUrl/subscription/subscription-list/ajax/subscription-list.jag"
    val payload = "action=getAllSubscriptions"

    post(url, payload, headers(cookie)).map { response =>
      (response.json \ "subscriptions" \ "applications").as[Seq[JsValue]].map { wso2applicationJson =>
        val name = (wso2applicationJson \ "name").as[String]
        val subscriptions = (wso2applicationJson \ "subscriptions").as[Seq[JsValue]].map { subscriptionJson =>
          WSO2API(
            (subscriptionJson \ "name").as[String],
            (subscriptionJson \ "version").as[String]
          )
        }
        name -> subscriptions
      }.toMap
    }
  }

  private def post(url: String, body: String, headers: Seq[(String, String)])
                  (implicit hc: HeaderCarrier): Future[HttpResponse] = {
    Logger.debug(s"POST url=$url request=$body")
    http.POSTString[HttpResponse](url, body, headers)
      .map { resp => handleResponse(url, resp) } recover {
      case e => throw new RuntimeException(s"Unexpected response from $url: ${e.getMessage}")
    }
  }

  private def get(url: String, headers: Seq[(String, String)])
                 (implicit rds: HttpReads[HttpResponse], hc: HeaderCarrier, ec: ExecutionContext): Future[HttpResponse] = {
    Logger.debug(s"GET url=$url")
    val headerCarrier = hc.withExtraHeaders(headers: _*)
    http.GET[HttpResponse](url)(rds, headerCarrier, ec)
      .map { resp => handleResponse(url, resp) }
  }

  private def handleResponse(url: String, response: HttpResponse): HttpResponse = {
    response.status match {
      case OK =>
        Logger.debug(s"Response=${response.body}")
        Try((response.json \ "error").as[Boolean]) match {
          case Success(false) =>
            response
          case Success(true) =>
            Logger.warn(s"Error found after calling $url: ${response.body}")
            throw new RuntimeException((response.json \ "message").as[String])
          case Failure(_) =>
            Logger.warn(s"Error found after calling $url: ${response.body}")
            throw new RuntimeException(s"${response.body}")
        }
      case _ =>
        Logger.warn(s"Request $url failed. Response=${response.body}")
        throw new RuntimeException(s"${response.body}")
    }
  }

  private def toHasSucceeded: HttpResponse => HasSucceeded = { _ => HasSucceeded }

  private def headers(): Seq[(String, String)] = {
    Seq(CONTENT_TYPE -> FORM)
  }

  private def headers(cookie: String): Seq[(String, String)] = {
    headers :+ (COOKIE -> cookie)
  }
}

case class Keys(consumerKey: String, consumerSecret: String, accessToken: String)

object Keys {
  implicit val formats: Format[Keys] = Json.format[Keys]
}
