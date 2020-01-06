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
import play.api.Logger
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ThirdPartyDelegatedAuthorityConnector @Inject()(httpClient: HttpClient, config: ThirdPartyDelegatedAuthorityConfig)(implicit val ec: ExecutionContext)  {

  def revokeApplicationAuthorities(clientId: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    httpClient.DELETE(s"${config.baseUrl}/authority/$clientId") map (_ => HasSucceeded) recover {
      case _: NotFoundException => HasSucceeded
    }
  }
}

case class ThirdPartyDelegatedAuthorityConfig(baseUrl: String)