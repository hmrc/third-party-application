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

package uk.gov.hmrc.connector

import javax.inject.Inject
import play.api.http.Status.CREATED
import uk.gov.hmrc.config.WSHttp
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.models.JsonFormatters._
import uk.gov.hmrc.models.TOTP

import scala.concurrent.{ExecutionContext, Future}

class TOTPConnector @Inject() extends HttpConnector {
  val http = WSHttp
  val serviceUrl = baseUrl("totp")

  def generateTotp()(implicit rds: HttpReads[HttpResponse], hc: HeaderCarrier, ec: ExecutionContext): Future[TOTP] = {
    val url = s"$serviceUrl/time-based-one-time-password/secret"

    http.POSTEmpty[HttpResponse](url).map { result =>
      result.status match {
        case CREATED => result.json.as[TOTP]
        case _ => throw new RuntimeException(s"Unexpected response from $url: (${result.status}) ${result.body}")
      }
    } recover {
      case e => throw new RuntimeException(s"Error response from $url: ${e.getMessage}")
    }
  }
}
