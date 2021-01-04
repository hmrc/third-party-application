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

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.auth.core.PlayAuthConnector
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.ExecutionContext

@Singleton
class AuthConnector @Inject()(val http: HttpClient, authConfig: AuthConfig)(implicit val ec: ExecutionContext) extends PlayAuthConnector  {
  lazy val serviceUrl: String = authConfig.baseUrl

}

case class AuthConfig(baseUrl: String,
                      userRole: String,
                      superUserRole: String,
                      adminRole: String,
                      enabled: Boolean,
                      canDeleteApplications: Boolean,
                      authorisationKey: String)
