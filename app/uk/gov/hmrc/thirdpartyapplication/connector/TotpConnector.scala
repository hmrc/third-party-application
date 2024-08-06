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
import scala.util.control.NonFatal

import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}

import uk.gov.hmrc.thirdpartyapplication.models.Totp

object TotpConnector {
  case class Config(baseUrl: String)
}

@Singleton
class TotpConnector @Inject() (httpClient: HttpClientV2, config: TotpConnector.Config)(implicit val ec: ExecutionContext) {

  def generateTotp()(implicit hc: HeaderCarrier): Future[Totp] = {
    val postUrl = url"${config.baseUrl}/time-based-one-time-password/secret"

    httpClient
      .post(postUrl)
      .execute[Totp]
      .recover {
        case e: UpstreamErrorResponse => throw new RuntimeException(s"Unexpected response from $postUrl: (${e.statusCode}, ${e.message})")
        case NonFatal(e)              => throw new RuntimeException(s"Error response from $postUrl: ${e.getMessage}")
      }
  }
}
