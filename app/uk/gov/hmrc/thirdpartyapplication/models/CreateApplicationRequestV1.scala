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

package uk.gov.hmrc.thirdpartyapplication.models

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._

case class CreateApplicationRequestV1(
    name: String,
    access: Access = Access.Standard(List.empty, None, None, Set.empty, None, None),
    description: Option[String] = None,
    environment: Environment,
    collaborators: Set[Collaborator],
    subscriptions: Option[Set[ApiIdentifier]]
  ) extends CreateApplicationRequest {

  private def validate(in: CreateApplicationRequestV1): Unit = {
    super.validate(in)
    in.access match {
      case a: Access.Standard => require(a.redirectUris.size <= 5, "maximum number of redirect URIs exceeded")
      case _                  =>
    }
  }

  validate(this)

  lazy val accessType = access.accessType

  def normaliseCollaborators: CreateApplicationRequestV1 = copy(collaborators = lowerCaseEmails(collaborators))

  def anySubscriptions: Set[ApiIdentifier] = subscriptions.getOrElse(Set.empty)
}

object CreateApplicationRequestV1 {
  import play.api.libs.json.Json

  implicit val reads = Json.reads[CreateApplicationRequestV1]
}
