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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.Totp
import uk.gov.hmrc.http.HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import uk.gov.hmrc.http.UpstreamErrorResponse

@Singleton
class TotpConnector @Inject()(httpClient: HttpClient, config: TotpConfig)(implicit val ec: ExecutionContext)   {

  def generateTotp()(implicit hc: HeaderCarrier): Future[Totp] = {
    val url = s"${config.baseUrl}/time-based-one-time-password/secret"

    httpClient.POSTEmpty[Totp](url)
    .recover {
      case e: UpstreamErrorResponse => throw new RuntimeException(s"Unexpected response from $url: (${e.statusCode}, ${e.message})")
      case NonFatal(e) => throw new RuntimeException(s"Error response from $url: ${e.getMessage}")
    }
  }
}

case class TotpConfig(baseUrl: String)
