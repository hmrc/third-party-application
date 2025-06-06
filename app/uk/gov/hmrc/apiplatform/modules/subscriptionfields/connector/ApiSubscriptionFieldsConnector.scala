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

package uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import play.api.http.Status._
import play.api.libs.json._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps, UpstreamErrorResponse}

import uk.gov.hmrc.apiplatform.modules.common.connectors.ResponseUtils
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ClientId, _}
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

object ApiSubscriptionFieldsConnector {
  case class Config(baseUrl: String)

  type FieldErrors = Map[FieldName, String]

  case class SubscriptionFieldsPutRequest(
      clientId: ClientId,
      apiContext: ApiContext,
      apiVersion: ApiVersionNbr,
      fields: Map[FieldName, FieldValue]
    )

  implicit val writeSubscriptionFieldsPutRequest: Writes[SubscriptionFieldsPutRequest] = Json.writes[SubscriptionFieldsPutRequest]
}

@Singleton
class ApiSubscriptionFieldsConnector @Inject() (httpClient: HttpClientV2, config: ApiSubscriptionFieldsConnector.Config)(implicit val ec: ExecutionContext) extends ResponseUtils {
  import ApiSubscriptionFieldsConnector._

  def delete(clientId: ClientId)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    httpClient.delete(url"${config.baseUrl}/field/application/$clientId")
      .execute[ErrorOr[Unit]]
      .map {
        case Right(_)                                        => HasSucceeded
        case Left(UpstreamErrorResponse(_, NOT_FOUND, _, _)) => HasSucceeded
        case Left(err)                                       => throw err
      }
  }

  def fetchAllFieldDefinitions()(implicit hc: HeaderCarrier): Future[ApiFieldMap[FieldDefinition]] = {
    import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models.Implicits.OverrideForBulkResponse._
    httpClient.get(url"${config.baseUrl}/definition")
      .execute[ApiFieldMap[FieldDefinition]]
  }

  def fetchFieldValues(clientId: ClientId)(implicit hc: HeaderCarrier): Future[ApiFieldMap[FieldValue]] = {
    import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models.Implicits.OverrideForBulkResponse._

    httpClient.get(url"${config.baseUrl}/field/application/$clientId")
      .execute[Option[ApiFieldMap[FieldValue]]]
      .map {
        case Some(map) => map
        case None      => Map.empty
      }
  }

  def urlSubscriptionFieldValues(clientId: ClientId, apiIdentifier: ApiIdentifier) =
    url"${config.baseUrl}/field/application/${clientId}/context/${apiIdentifier.context}/version/${apiIdentifier.versionNbr}"

  def saveFieldValues(clientId: ClientId, apiIdentifier: ApiIdentifier, fields: Map[FieldName, FieldValue])(implicit hc: HeaderCarrier): Future[Either[FieldErrors, Unit]] = {
    if (fields.isEmpty) {
      successful(Right(()))
    } else {
      httpClient.put(urlSubscriptionFieldValues(clientId, apiIdentifier))
        .withBody(Json.toJson(SubscriptionFieldsPutRequest(clientId, apiIdentifier.context, apiIdentifier.versionNbr, fields)))
        .execute[HttpResponse]
        .map { response =>
          response.status match {
            case BAD_REQUEST  =>
              Json.parse(response.body).validate[Map[FieldName, String]] match {
                case s: JsSuccess[Map[FieldName, String]] => Left(s.get)
                case _                                    => Left(Map.empty)
              }
            case OK | CREATED => Right(())
            case statusCode   => throw UpstreamErrorResponse("Failed to put subscription fields", statusCode)
          }
        }
    }
  }
}
