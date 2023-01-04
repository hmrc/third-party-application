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

package uk.gov.hmrc.apiplatform.modules.gkauth.connectors

import javax.inject.{Inject, Singleton}

import uk.gov.hmrc.auth.core._
import uk.gov.hmrc.http.HttpClient

object StrideAuthConnector {
  case class Config(strideAuthBaseUrl: String)
}

@Singleton
class StrideAuthConnector @Inject()(val http: HttpClient, config: StrideAuthConnector.Config) extends PlayAuthConnector {
  lazy val serviceUrl = config.strideAuthBaseUrl
}

