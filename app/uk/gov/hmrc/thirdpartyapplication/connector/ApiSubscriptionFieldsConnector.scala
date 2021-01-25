/*
 * Copyright 2021 HM Revenue & Customs
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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.http.HttpReads.Implicits._
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.http.UpstreamErrorResponse
import play.api.http.Status._

@Singleton
class ApiSubscriptionFieldsConnector @Inject()(httpClient: HttpClient, config: ApiSubscriptionFieldsConfig)(implicit val ec: ExecutionContext) extends ResponseUtils {

  def deleteSubscriptions(clientId: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    httpClient.DELETE[ErrorOr[Unit]](s"${config.baseUrl}/field/application/$clientId")
    .map {
      case Right(_) => HasSucceeded
      case Left(UpstreamErrorResponse(_, NOT_FOUND, _, _)) => HasSucceeded
      case Left(err) => throw err
    }
  }
}

case class ApiSubscriptionFieldsConfig(baseUrl: String)
