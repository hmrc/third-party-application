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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import scala.collection._
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.ActorSystem

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.RateLimitTier
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

trait ApiGatewayStore extends EitherTHelper[String] {

  /*
   * API-3862: As a legacy of out use of WSO2, we had an identifer for applications named 'wso2ApplicationName'. This is the name of the property in the
   * Application document in Mongo, and are used to identify applications in AWS API Gateway. As such, the parameters for createApplication() and
   * deleteApplication() are still called 'wso2ApplicationName' to make it clear that these values are distinct from the other Application names/identifiers.
   */

  def createApplication(wso2ApplicationName: String, accessToken: String)(implicit hc: HeaderCarrier): Future[HasSucceeded]

  def deleteApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier): Future[HasSucceeded]

  def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)(implicit hc: HeaderCarrier): Future[HasSucceeded]
}

@Singleton
class AwsApiGatewayStore @Inject() (awsApiGatewayConnector: AwsApiGatewayConnector)(implicit val actorSystem: ActorSystem, val ec: ExecutionContext) extends ApiGatewayStore {

  override def createApplication(wso2ApplicationName: String, accessToken: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    for {
      _ <- awsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, accessToken, RateLimitTier.BRONZE)(hc)
    } yield HasSucceeded
  }

  override def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)(implicit hc: HeaderCarrier): Future[HasSucceeded] =
    awsApiGatewayConnector.createOrUpdateApplication(app.wso2ApplicationName, app.tokens.production.accessToken, rateLimitTier)(hc)

  override def deleteApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] =
    awsApiGatewayConnector.deleteApplication(wso2ApplicationName)(hc)
}

@Singleton
class StubApiGatewayStore @Inject() (implicit val ec: ExecutionContext) extends ApiGatewayStore {

  lazy val stubApplications: concurrent.Map[String, mutable.ListBuffer[ApiIdentifier]] = concurrent.TrieMap()

  override def createApplication(wso2ApplicationName: String, accessToken: String)(implicit hc: HeaderCarrier) = Future.successful {
    stubApplications += (wso2ApplicationName -> mutable.ListBuffer.empty)
    HasSucceeded
  }

  override def deleteApplication(wso2ApplicationName: String)(implicit hc: HeaderCarrier) = Future.successful {
    stubApplications -= wso2ApplicationName
    HasSucceeded
  }

  override def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

}
