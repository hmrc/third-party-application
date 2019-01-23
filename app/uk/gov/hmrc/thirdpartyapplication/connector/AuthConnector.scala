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

import javax.inject.Inject

import uk.gov.hmrc.thirdpartyapplication.config.WSHttp
import uk.gov.hmrc.http.{HeaderCarrier, Upstream4xxResponse}
import uk.gov.hmrc.thirdpartyapplication.models.AuthRole

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AuthConnector @Inject() extends HttpConnector {

  val authUrl: String = s"${baseUrl("auth")}/auth/authenticate/user"
  val http = WSHttp

  def authorized(role: AuthRole)(implicit hc: HeaderCarrier): Future[Boolean] = authorized(role.scope, Some(role.name))

  def authorized(scope: String, role: Option[String])(implicit hc: HeaderCarrier): Future[Boolean] = {
    val authoriseUrl =
      role.map(aRole =>  s"$authUrl/authorise?scope=$scope&role=$aRole")
        .getOrElse(s"$authUrl/authorise?scope=$scope")

    http.GET(authoriseUrl) map (_ => true) recover {
      case e: Upstream4xxResponse if e.upstreamResponseCode == 401 => false
    }
  }
}
