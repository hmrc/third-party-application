/*
 * Copyright 2025 HM Revenue & Customs
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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApiIdentifier
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models.{ApiFieldMap, FieldValue}

final case class QueriedApplicationWithOptionalToken(
    details: CoreApplication,
    collaborators: Set[Collaborator],
    subscriptions: Option[Set[ApiIdentifier]],
    fieldValues: Option[ApiFieldMap[FieldValue]],
    stateHistory: Option[List[StateHistory]],
    serverToken: Option[String]
  )

object QueriedApplicationWithOptionalToken {
  import play.api.libs.json.Format
  import play.api.libs.json.Json

  implicit val format: Format[QueriedApplicationWithOptionalToken] = Json.format[QueriedApplicationWithOptionalToken]

  // These are just moving from one shape to another
  // $COVERAGE-OFF$
  def apply(app: ApplicationWithCollaborators): QueriedApplicationWithOptionalToken = QueriedApplicationWithOptionalToken(app.details, app.collaborators, None, None, None, None)

  def apply(app: ApplicationWithSubscriptions): QueriedApplicationWithOptionalToken =
    QueriedApplicationWithOptionalToken(app.details, app.collaborators, Some(app.subscriptions), None, None, None)

  def apply(app: ApplicationWithSubscriptionFields): QueriedApplicationWithOptionalToken =
    QueriedApplicationWithOptionalToken(app.details, app.collaborators, Some(app.subscriptions), Some(app.fieldValues), None, None)
  // $COVERAGE-ON$
}
