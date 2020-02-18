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

package uk.gov.hmrc.thirdpartyapplication.services

import java.security.SecureRandom
import java.util.UUID

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.AwsApiGatewayConnector
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import scala.collection._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

trait ApiGatewayStore {

  /*
   * API-3862: As a legacy of out use of WSO2, we had an identifer for applications named 'wso2ApplicationName'. This is the name of the property in the
   * Application document in Mongo, and are used to identify applications in AWS API Gateway. As such, the parameters for createApplication() and
   * deleteApplication() are still called 'wso2ApplicationName' to make it clear that these values are distinct from the other Application names/identifiers.
   */

  def createApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier): Future[EnvironmentToken]

  def deleteApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier): Future[HasSucceeded]

  def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)(implicit hc: HeaderCarrier): Future[HasSucceeded]

}

@Singleton
class AwsApiGatewayStore @Inject()(awsApiGatewayConnector: AwsApiGatewayConnector)(implicit val actorSystem: ActorSystem) extends ApiGatewayStore {

  private def generateEnvironmentToken(): EnvironmentToken = {
    val randomBytes: Array[Byte] = new Array[Byte](16) // scalastyle:off magic.number
    new SecureRandom().nextBytes(randomBytes)
    val accessToken = randomBytes.map("%02x".format(_)).mkString
    EnvironmentToken((Random.alphanumeric take 28).mkString, "", accessToken)
  }

  override def createApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier): Future[EnvironmentToken] = {
    val environmentToken = generateEnvironmentToken()
    for {
      _ <- awsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, environmentToken.accessToken, BRONZE)(hc)
    } yield environmentToken
  }

  override def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)(implicit hc: HeaderCarrier): Future[HasSucceeded] =
    awsApiGatewayConnector.createOrUpdateApplication(app.wso2ApplicationName, app.tokens.production.accessToken, rateLimitTier)(hc)

  override def deleteApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] =
    awsApiGatewayConnector.deleteApplication(wso2ApplicationName)(hc)

}

@Singleton
class StubApiGatewayStore @Inject()() extends ApiGatewayStore {

  def dummyEnvironmentToken = EnvironmentToken(s"dummy-${UUID.randomUUID()}", "dummyValue", "dummyValue")

  lazy val stubApplications: concurrent.Map[String, mutable.ListBuffer[APIIdentifier]] = concurrent.TrieMap()

  override def createApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier) = Future.successful {
    stubApplications += (wso2ApplicationName -> mutable.ListBuffer.empty)
    dummyEnvironmentToken
  }

  override def deleteApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier) = Future.successful {
    stubApplications -= wso2ApplicationName
    HasSucceeded
  }

  override def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)
                                (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

}
