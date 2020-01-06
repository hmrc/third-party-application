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

package uk.gov.hmrc.thirdpartyapplication.connector

import javax.inject.{Inject, Singleton}
import play.api.http.Status.CREATED
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.Totp

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class TotpConnector @Inject()(httpClient: HttpClient, config: TotpConfig)(implicit val ec: ExecutionContext)   {

  def generateTotp()(implicit rds: HttpReads[HttpResponse], hc: HeaderCarrier, ec: ExecutionContext): Future[Totp] = {
    val url = s"${config.baseUrl}/time-based-one-time-password/secret"

    httpClient.POSTEmpty[HttpResponse](url).map { result =>
      result.status match {
        case CREATED => result.json.as[Totp]
        case _ => throw new RuntimeException(s"Unexpected response from $url: (${result.status}) ${result.body}")
      }
    } recover {
      case e => throw new RuntimeException(s"Error response from $url: ${e.getMessage}")
    }
  }
}

case class TotpConfig(baseUrl: String)
