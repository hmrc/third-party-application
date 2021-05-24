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

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

@Singleton
class ThirdPartyDelegatedAuthorityConnector @Inject()(httpClient: HttpClient, config: ThirdPartyDelegatedAuthorityConfig)(implicit val ec: ExecutionContext)  {

  def revokeApplicationAuthorities(clientId: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    httpClient.DELETE[Option[Unit]](s"${config.baseUrl}/authority/$clientId") map (_ => HasSucceeded)
  }
}

case class ThirdPartyDelegatedAuthorityConfig(baseUrl: String)
