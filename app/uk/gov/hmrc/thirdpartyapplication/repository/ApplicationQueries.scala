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

package uk.gov.hmrc.thirdpartyapplication.repository

import java.time.Instant

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ClientId, Environment, UserId, _}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._
import uk.gov.hmrc.thirdpartyapplication.controllers.query._

object ApplicationQueries {

  def allApplications(excludeDeleted: Boolean = true) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = if (excludeDeleted) ExcludeDeletedQP :: Nil else Nil
  )

  def applicationByClientId(clientId: ClientId) = ApplicationQuery.ByClientId(clientId, false, List(ExcludeDeletedQP))

  def applicationByServerToken(serverToken: String) = ApplicationQuery.ByServerToken(serverToken, false, List(ExcludeDeletedQP))

  lazy val standardNonTestingApps = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    sorting = Sorting.NoSorting,
    params = List(
      AccessTypeQP(Some(AccessType.STANDARD)),
      AppStateFilterQP(AppStateFilter.MatchingMany(State.values.toSet[State] - State.TESTING - State.DELETED))
    )
  )

  def applicationsByName(name: String) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    sorting = Sorting.NoSorting,
    params = List(
      NameQP(name),
      EnvironmentQP(Environment.PRODUCTION),
      ExcludeDeletedQP
    )
  )

  def applicationsByVerifiableUplift(verificationCode: String) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = List(
      VerificationCodeQP(verificationCode),
      ExcludeDeletedQP
    )
  )

  def applicationsByUserId(userId: UserId, includeDeleted: Boolean) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = UserIdQP(userId) :: WantSubscriptionsQP :: List(ExcludeDeletedQP).filterNot(_ => includeDeleted)
  )

  def applicationsByStateAndDate(state: State, beforeDate: Instant) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = AppStateFilterQP(AppStateFilter.MatchingOne(state)) :: AppStateBeforeDateQP(beforeDate) :: Nil
  )

  def applicationsByUserIdAndEnvironment(userId: UserId, environment: Environment) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = UserIdQP(userId) :: WantSubscriptionsQP :: ExcludeDeletedQP :: EnvironmentQP(environment) :: Nil
  )

  def applicationsByApiContext(apiContext: ApiContext) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = ApiContextQP(apiContext) :: Nil
  )

  def applicationsByApiIdentifier(apiIdentifier: ApiIdentifier) = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = ApiContextQP(apiIdentifier.context) :: ApiVersionNbrQP(apiIdentifier.versionNbr) :: Nil
  )

  val applicationsByNoSubscriptions = ApplicationQuery.GeneralOpenEndedApplicationQuery(
    params = NoSubscriptionsQP :: Nil
  )
}
