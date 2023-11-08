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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import play.api.http.ContentTypes.JSON
import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.libs.json.{JsPath, Reads}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

object AwsApiGatewayConnector extends ApplicationLogger {
  case class Config(baseUrl: String, awsApiKey: String)

  private[connector] case class UpdateApplicationUsagePlanRequest(apiKeyName: String, apiKeyValue: String)

  private[connector] object UpdateApplicationUsagePlanRequest {
    implicit val writes = play.api.libs.json.Json.writes[UpdateApplicationUsagePlanRequest]
  }

  private[connector] case class RequestId(value: String) extends AnyVal

  private[connector] object RequestId {
    implicit val reads: Reads[RequestId] = (JsPath \ "RequestId").read[String].map(RequestId(_))
  }
}

@Singleton
class AwsApiGatewayConnector @Inject() (http: HttpClient, config: AwsApiGatewayConnector.Config)(implicit val ec: ExecutionContext) {
  import AwsApiGatewayConnector._

  val serviceBaseUrl: String = s"${config.baseUrl}/v1/application"
  val awsApiKey: String      = config.awsApiKey
  val apiKeyHeaderName       = "x-api-key"

  private def updateUsagePlanURL(rateLimitTier: RateLimitTier): String = s"${config.baseUrl}/v1/usage-plans/$rateLimitTier/api-keys"
  private def deleteAPIKeyURL(applicationName: String): String         = s"${config.baseUrl}/v1/api-keys/$applicationName"

  def createOrUpdateApplication(applicationName: String, serverToken: String, usagePlan: RateLimitTier)(hc: HeaderCarrier): Future[HasSucceeded] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc
      .copy(authorization = None)
      .withExtraHeaders(apiKeyHeaderName -> awsApiKey, CONTENT_TYPE -> JSON)

    http.POST[UpdateApplicationUsagePlanRequest, RequestId](
      updateUsagePlanURL(usagePlan),
      UpdateApplicationUsagePlanRequest(applicationName, serverToken)
    )
      .map { requestId =>
        logger.info(s"Successfully created or updated application '$applicationName' in AWS API Gateway with request ID ${requestId.value}")
        HasSucceeded
      }
  }

  def deleteApplication(applicationName: String)(hc: HeaderCarrier): Future[HasSucceeded] = {
    implicit val headersWithoutAuthorization: HeaderCarrier = hc
      .copy(authorization = None)
      .withExtraHeaders(apiKeyHeaderName -> awsApiKey)

    http.DELETE[RequestId](deleteAPIKeyURL(applicationName)).map(requestId => {
      logger.info(s"Successfully deleted application '$applicationName' from AWS API Gateway with request ID ${requestId.value}")
      HasSucceeded
    })
  }
}
