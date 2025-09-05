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

package uk.gov.hmrc.thirdpartyapplication.models.db

import java.time.Instant
import java.time.temporal.ChronoUnit

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ClientSecret

case class StoredClientSecret(
    name: String,
    createdOn: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    lastAccess: Option[Instant] = None,
    id: ClientSecret.Id = ClientSecret.Id.random,
    hashedSecret: String
  ) {

  def asClientSecret = StoredClientSecret.asClientSecret(this)
}

object StoredClientSecret {
  def asClientSecret(in: StoredClientSecret): ClientSecret = {
    ClientSecret(
      in.id,
      in.name,
      in.createdOn,
      in.lastAccess
    )
  }
}