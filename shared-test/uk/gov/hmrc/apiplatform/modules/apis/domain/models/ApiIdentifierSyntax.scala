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

package uk.gov.hmrc.apiplatform.modules.apis.domain.models

import uk.gov.hmrc.apiplatform.modules.common.domain.models._

trait ApiIdentifierSyntax {

  implicit class ApiContextSyntax(value: String) {
    def asContext: ApiContext = ApiContext(value)
  }

  implicit class ApiVersionSyntax(value: String) {
    def asVersion: ApiVersionNbr = ApiVersionNbr(value)
  }

  implicit class ApiIdentifierStringSyntax(context: String) {
    def asIdentifier: ApiIdentifier                  = ApiContext(context).asIdentifier
    def asIdentifier(version: String): ApiIdentifier = ApiIdentifier(ApiContext(context), ApiVersionNbr(version))
  }

  implicit class ApiIdentifierContextSyntax(context: ApiContext) {
    def asIdentifier: ApiIdentifier                  = ApiIdentifier(context, ApiVersionNbr("1.0"))
    def asIdentifier(version: String): ApiIdentifier = ApiIdentifier(context, ApiVersionNbr(version))
  }
}

object ApiIdentifierSyntax extends ApiIdentifierSyntax
