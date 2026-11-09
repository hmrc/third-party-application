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

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, Period}

import com.typesafe.config.ConfigFactory

import uk.gov.hmrc.apiplatform.modules.common.domain.models.*
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, AccessType}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.*
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.*
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ImportantSubmissionData

case class StoredApplication(
    id: ApplicationId,
    name: ApplicationName,
    normalisedName: String,
    collaborators: Set[Collaborator],
    description: Option[String] = None,
    tokens: ApplicationTokens,
    state: ApplicationState,
    access: Access = Access.Standard(),
    createdOn: Instant,
    lastAccess: Instant,
    refreshTokensAvailableFor: Period = Period.ofDays(grantLengthConfig),
    rateLimitTier: Option[RateLimitTier] = Some(RateLimitTier.Bronze),
    environment: Environment = Environment.Production,
    checkInformation: Option[CheckInformation] = None,
    blocked: Boolean = false,
    ipAllowlist: IpAllowlist = IpAllowlist(),
    deleteRestriction: DeleteRestriction = DeleteRestriction.NoRestriction,
    organisationId: Option[OrganisationId] = None
  ) extends HasState with HasAccess with HasCollaborators with HasEnvironment {
  override val deployedTo = environment

  def sellResellOrDistribute = access match {
    case Access.Standard(_, _, _, _, _, sellResellOrDistribute, _) => sellResellOrDistribute
    case _                                                         => None
  }

  def importantSubmissionData: Option[ImportantSubmissionData] = access match {
    case Access.Standard(_, _, _, _, _, _, Some(submissionData)) => Some(submissionData)
    case _                                                       => None
  }

  import monocle.syntax.all.*
  def withState(newState: ApplicationState): StoredApplication = this.focus(_.state).replace(newState)
  def withAccess(newAccess: Access): StoredApplication         = this.focus(_.access).replace(newAccess)

  lazy val asAppWithCollaborators = StoredApplication.asAppWithCollaborators(this)

  lazy val asQueriedApplication = QueriedApplication(asAppWithCollaborators)
}

object StoredApplication {
  import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper.*

  private val initialLastAccessDate = LocalDate.of(2019, 6, 25)

  def deriveLastAccess(createdOn: Instant, lastAccess: Instant): Option[Instant] = {
    Some(lastAccess)
      .filter(lad => ChronoUnit.SECONDS.between(createdOn, lad) > 0)
      .filter(lad => ChronoUnit.DAYS.between(initialLastAccessDate, lad.asLocalDate) > 0)
  }

  def asAppWithCollaborators(data: StoredApplication): ApplicationWithCollaborators = {
    ApplicationWithCollaborators(
      CoreApplication(
        data.id,
        data.tokens.production.asApplicationToken,
        data.name,
        data.environment,
        data.description,
        data.createdOn,
        deriveLastAccess(data.createdOn, data.lastAccess),
        GrantLength.apply(data.refreshTokensAvailableFor).getOrElse(GrantLength.EIGHTEEN_MONTHS),
        data.access,
        data.state,
        data.rateLimitTier.getOrElse(RateLimitTier.Bronze),
        data.checkInformation,
        data.blocked,
        ipAllowlist = data.ipAllowlist,
        lastActionActor = ActorType.Unknown,
        deleteRestriction = data.deleteRestriction,
        organisationId = data.organisationId
      ),
      data.collaborators
    )
  }

  val grantLengthConfig = ConfigFactory.load().getInt("grantLengthInDays")

  def create(
      createApplicationRequest: CreateApplicationRequest,
      productionToken: StoredToken,
      createdOn: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    ): StoredApplication = {
    import createApplicationRequest.*

    val applicationState = (environment, accessType) match {
      case (Environment.Sandbox, _)                     => ApplicationState(State.Production, updatedOn = createdOn)
      case (_, AccessType.Privileged | AccessType.Ropc) => ApplicationState(State.Production, collaborators.headOption.map(_.emailAddress.text), updatedOn = createdOn)
      case _                                            => ApplicationState(State.Testing, updatedOn = createdOn)
    }

    val checkInfo = createApplicationRequest match {
      case v1: CreateApplicationRequestV1 if (v1.anySubscriptions.nonEmpty) => Some(CheckInformation(apiSubscriptionsConfirmed = true))
      case _: CreateApplicationRequestV2                                    => None
      case _: CreateApplicationRequestV1                                    => None
    }

    val applicationAccess: Access = createApplicationRequest match {
      case v1: CreateApplicationRequestV1 => v1.access match {
          case CreationAccess.Standard   => Access.Standard()
          case CreationAccess.Privileged => Access.Privileged()
        }
      case v2: CreateApplicationRequestV2 =>
        Access.Standard().copy(
          redirectUris = v2.access.redirectUris,
          postLogoutRedirectUris = v2.access.postLogoutRedirectUris,
          overrides = v2.access.overrides,
          sellResellOrDistribute = Some(v2.upliftRequest.sellResellOrDistribute)
        )
    }

    StoredApplication(
      ApplicationId.random,
      name,
      name.toLowerCase,
      collaborators,
      createApplicationRequest.description.filterNot(_ => environment.isProduction),
      ApplicationTokens(productionToken),
      applicationState,
      applicationAccess,
      createdOn,
      createdOn,
      environment = environment,
      checkInformation = checkInfo,
      organisationId = createApplicationRequest.organisationId
    )
  }
}
