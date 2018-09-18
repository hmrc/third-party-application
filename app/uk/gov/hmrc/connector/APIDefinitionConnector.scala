/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID

import javax.inject.Inject
import uk.gov.hmrc.config.WSHttp
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads}
import uk.gov.hmrc.models.APIDefinition

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class APIDefinitionConnector @Inject() extends HttpConnector {
  lazy val serviceUrl = baseUrl("api-definition")
  val http = WSHttp

  def fetchAllAPIs(applicationId: UUID)(implicit rds: HttpReads[Seq[APIDefinition]], hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[APIDefinition]] = {
    val url = s"$serviceUrl/api-definition?applicationId=$applicationId"
    http.GET[Seq[APIDefinition]](url).map(result => result) recover {
      case e => throw new RuntimeException(s"Unexpected response from $url: ${e.getMessage}")
    }
  }
}
