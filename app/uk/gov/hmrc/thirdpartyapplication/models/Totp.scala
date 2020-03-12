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

package uk.gov.hmrc.thirdpartyapplication.models


case class Totp(secret: String, id: String)

case class ApplicationTotp(production: Totp, secret: String, id: String) {
  def toId: TotpId = TotpId(id)
  def toSecret: TotpSecret = TotpSecret(secret)
}

object ApplicationTotp {
  def apply(production: Totp): ApplicationTotp = ApplicationTotp(production, production.secret, production.id)
}

case class TotpId(production: String, id: String)

case class TotpSecret(production: String, secret: String)

object TotpSecret {
  def apply(secret: String) = new TotpSecret(secret, secret)
}

object TotpId {
  def apply(id: String) = new TotpId(id, id)
}
