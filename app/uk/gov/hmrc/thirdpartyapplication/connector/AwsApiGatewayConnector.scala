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

package uk.gov.hmrc.thirdpartyapplication.connector

import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.http.ContentTypes.JSON
import play.api.http.HeaderNames.CONTENT_TYPE
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class AwsApiGatewayConnector @Inject()(http: HttpClient, config: AwsApiGatewayConfig)
                                      (implicit val ec: ExecutionContext) {

  val serviceBaseUrl: String = s"${config.baseUrl}/v1/application"
  val awsApiKey: String = config.awsApiKey
  val apiKeyHeaderName = "x-api-key"

  private def updateUsagePlanURL(rateLimitTier: RateLimitTier): String = s"${config.baseUrl}/v1/usage-plans/$rateLimitTier/api-keys"
  private def deleteAPIKeyURL(applicationName: String): String = s"${config.baseUrl}/v1/api-keys/$applicationName"

  def createOrUpdateApplication(applicationName: String, serverToken: String, usagePlan: RateLimitTier)(hc: HeaderCarrier): Future[HasSucceeded] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc
      .copy(authorization = None)
      .withExtraHeaders(apiKeyHeaderName -> awsApiKey, CONTENT_TYPE -> JSON)

    http.POST(
      updateUsagePlanURL(usagePlan),
      UpdateApplicationUsagePlanRequest(applicationName, serverToken))
    .map { result =>
      val requestId = (result.json \ "RequestId").as[String]
      Logger.info(s"Successfully created or updated application '$applicationName' in AWS API Gateway with request ID $requestId")
      HasSucceeded
    } recover {
      awsRecovery(s"Failed to create or update application '$applicationName' in AWS API Gateway")
    }
  }

  def deleteApplication(applicationName: String)(hc: HeaderCarrier): Future[HasSucceeded] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc
      .copy(authorization = None)
      .withExtraHeaders(apiKeyHeaderName -> awsApiKey)

    http.DELETE(deleteAPIKeyURL(applicationName)) map { result =>
      val requestId = (result.json \ "RequestId").as[String]
      Logger.info(s"Successfully deleted application '$applicationName' from AWS API Gateway with request ID $requestId")
      HasSucceeded
    } recover {
      awsRecovery(s"Failed to delete application '$applicationName' from AWS API Gateway")
    }
  }

  // we don't want the AWS API Gateway to cause failures until we make the switch from WSO2
  private def awsRecovery(errorMessage: String): PartialFunction[Throwable, HasSucceeded] = {
    case NonFatal(e) =>
      Logger.error(errorMessage, e)
      HasSucceeded
  }
}

case class AwsApiGatewayConfig(baseUrl: String, awsApiKey: String)
case class UpdateApplicationUsagePlanRequest(apiKeyName: String, apiKeyValue: String)
