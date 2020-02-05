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

import javax.inject.Inject
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models.UserResponse

import scala.concurrent.{ExecutionContext, Future}

class ThirdPartyDeveloperConnector @Inject()(httpClient: HttpClient, config: ThirdPartyDeveloperConfig)(implicit val ec: ExecutionContext)  {

  val FetchUsersByEmailAddressesURL: String = s"${config.baseUrl}/developers/get-by-emails"

  def fetchUsersByEmailAddresses(emailAddresses: Set[String])(implicit rds: HttpReads[Seq[UserResponse]], hc: HeaderCarrier): Future[Seq[UserResponse]] =
    httpClient.POST[FetchUsersByEmailAddressesRequest, Seq[UserResponse]](FetchUsersByEmailAddressesURL, FetchUsersByEmailAddressesRequest(emailAddresses))
      .recover {
        case e => throw new RuntimeException(s"Unexpected response from $FetchUsersByEmailAddressesURL: ${e.getMessage}")
      }
}

case class FetchUsersByEmailAddressesRequest(emailAddresses: Set[String])

case class ThirdPartyDeveloperConfig(baseUrl: String)