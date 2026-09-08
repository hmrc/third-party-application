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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.QueriedApplication
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models.{ApiFieldMap, FieldValue}
import uk.gov.hmrc.thirdpartyapplication.models.db._

case class QueriedStoredApplication(
    app: StoredApplication,
    subscriptions: Option[Set[ApiIdentifier]] = None,
    fieldValues: Option[ApiFieldMap[FieldValue]] = None,
    stateHistory: Option[List[StateHistory]] = None
  ) {

  def asQueriedApplication = {
    val awc = app.asAppWithCollaborators
    QueriedApplication(
      details = awc.details,
      collaborators = awc.collaborators,
      subscriptions = subscriptions,
      fieldValues = fieldValues,
      stateHistory = stateHistory
    )
  }

  def asQueriedApplicationWithOptionalToken(allowServerToken: Boolean): QueriedApplicationWithOptionalToken = {
    val awc              = app.asAppWithCollaborators
    val maybeServerToken = Some(app.tokens.production.accessToken).filter(_ => allowServerToken)

    QueriedApplicationWithOptionalToken(
      details = awc.details,
      collaborators = awc.collaborators,
      subscriptions = subscriptions,
      fieldValues = fieldValues,
      stateHistory = stateHistory,
      serverToken = maybeServerToken
    )
  }
}
