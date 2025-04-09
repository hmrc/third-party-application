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

import play.api.http.Status._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ClientId
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

object ApiSubscriptionFieldsConnector {
  case class Config(baseUrl: String)
}

@Singleton
class ApiSubscriptionFieldsConnector @Inject() (httpClient: HttpClientV2, config: ApiSubscriptionFieldsConnector.Config)(implicit val ec: ExecutionContext) extends ResponseUtils {

  def delete(clientId: ClientId)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    httpClient.delete(url"${config.baseUrl}/field/application/$clientId")
      .execute[ErrorOr[Unit]]
      .map {
        case Right(_)                                        => HasSucceeded
        case Left(UpstreamErrorResponse(_, NOT_FOUND, _, _)) => HasSucceeded
        case Left(err)                                       => throw err
      }
  }

  // def fetchAllForApp(clientId: ClientId)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
  //   httpClient.get(url"${config.baseUrl}/field/application/$clientId")
  //     .execute[ErrorOr[Unit]]
  //     .map {
  //       case Right(_)                                        => HasSucceeded
  //       case Left(UpstreamErrorResponse(_, NOT_FOUND, _, _)) => HasSucceeded
  //       case Left(err)                                       => throw err
  //     }
  // }
}
