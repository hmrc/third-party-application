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

import java.util.UUID

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads}
import uk.gov.hmrc.thirdpartyapplication.models.ApiDefinition
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApiDefinitionConnector @Inject()(httpClient: HttpClient, config: ApiDefinitionConfig)(implicit val ec: ExecutionContext)  {

  def fetchAllAPIs(applicationId: UUID)(implicit rds: HttpReads[Seq[ApiDefinition]], hc: HeaderCarrier, ec: ExecutionContext): Future[Seq[ApiDefinition]] = {
    val url = s"${config.baseUrl}/api-definition?applicationId=$applicationId"
    httpClient.GET[Seq[ApiDefinition]](url).map(result => result) recover {
      case e => throw new RuntimeException(s"Unexpected response from $url: ${e.getMessage}")
    }
  }
}

case class ApiDefinitionConfig(baseUrl: String)
