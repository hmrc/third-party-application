/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import scala.util.Random
import play.api.libs.json.Json

case class ApiContext(value: String) extends AnyVal
case class ApiVersion(value: String) extends AnyVal

case class ApiIdentifier(context: ApiContext, version: ApiVersion) {

  def asText(separator: String): String = s"${context.value}$separator${version.value}"
}

object ApiContext {

  implicit val ordering: Ordering[ApiContext] = new Ordering[ApiContext] {
    override def compare(x: ApiContext, y: ApiContext): Int = x.value.compareTo(y.value)
  }
  def random                                  = ApiContext(Random.alphanumeric.take(10).mkString)

  implicit val jsonFormat = Json.valueFormat[ApiContext]

}

object ApiVersion {

  implicit val ordering: Ordering[ApiVersion] = new Ordering[ApiVersion] {
    override def compare(x: ApiVersion, y: ApiVersion): Int = x.value.compareTo(y.value)
  }
  def random                                  = ApiVersion(Random.nextDouble().toString)

  implicit val jsonFormat = Json.valueFormat[ApiVersion]
}

object ApiIdentifier {
  def random = ApiIdentifier(ApiContext.random, ApiVersion.random)

  implicit val jsonFormat = Json.format[ApiIdentifier]

}

trait ApiIdentifierSyntax {

  implicit class ApiContextSyntax(value: String) {
    def asContext: ApiContext = ApiContext(value)
  }

  implicit class ApiVersionSyntax(value: String) {
    def asVersion: ApiVersion = ApiVersion(value)
  }

  implicit class ApiIdentifierStringSyntax(context: String) {
    def asIdentifier: ApiIdentifier                  = ApiContext(context).asIdentifier
    def asIdentifier(version: String): ApiIdentifier = ApiIdentifier(ApiContext(context), ApiVersion(version))
  }

  implicit class ApiIdentifierContextSyntax(context: ApiContext) {
    def asIdentifier: ApiIdentifier                  = ApiIdentifier(context, ApiVersion("1.0"))
    def asIdentifier(version: String): ApiIdentifier = ApiIdentifier(context, ApiVersion(version))
  }
}

object ApiIdentifierSyntax extends ApiIdentifierSyntax
