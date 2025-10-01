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

package uk.gov.hmrc.apiplatform.modules.uplift.services

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQueries
import uk.gov.hmrc.apiplatform.modules.uplift.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, UpliftVerified}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, AuditHelper, AuditService}

@Singleton
class UpliftService @Inject() (
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    applicationNamingService: UpliftNamingService,
    apiGatewayStore: ApiGatewayStore,
    val clock: Clock
  )(implicit ec: ExecutionContext
  ) extends ApplicationLogger with ClockNow {

  def verifyUplift(verificationCode: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {

    def verifyProduction(app: StoredApplication) = {
      logger.info(s"Application uplift for '${app.name}' has been verified already. No update was executed.")
      successful(UpliftVerified)
    }

    def findLatestUpliftRequester(applicationId: ApplicationId): Future[String] =
      for {
        history <- stateHistoryRepository.fetchLatestByStateForApplication(applicationId, State.PENDING_GATEKEEPER_APPROVAL)
        state    = history.getOrElse(throw new RuntimeException(s"Pending state not found for application: ${applicationId}"))
      } yield state.actor match {
        case Actors.Unknown                => "Unknown"
        case Actors.AppCollaborator(email) => email.text
        case Actors.GatekeeperUser(user)   => user
        case Actors.ScheduledJob(jobId)    => jobId
        case Actors.Process(name)          => name
      }

    def audit(app: StoredApplication) =
      findLatestUpliftRequester(app.id) flatMap { email =>
        auditService.audit(ApplicationUpliftVerified, AuditHelper.applicationId(app.id), Map("upliftRequestedByEmail" -> email))
      }

    def verifyPending(app: StoredApplication) = for {
      _ <- apiGatewayStore.createApplication(app.wso2ApplicationName, app.tokens.production.accessToken)
      _ <- applicationRepository.save(app.withState(app.state.toPreProduction(instant())))
      _ <- insertStateHistory(
             app,
             State.PRE_PRODUCTION,
             Some(State.PENDING_REQUESTER_VERIFICATION),
             Actors.AppCollaborator(LaxEmailAddress(app.state.requestedByEmailAddress.get)),
             (a: StoredApplication) => applicationRepository.save(a)
           )
      _  = logger.info(s"UPLIFT02: Application uplift for application:${app.name} appId:${app.id} has been verified successfully")
      _  = audit(app)
    } yield UpliftVerified

    for {
      app <- applicationRepository.fetchApplications(ApplicationQueries.applicationsByVerifiableUplift(verificationCode))
               .map(
                 _.headOption
                   .getOrElse(throw InvalidUpliftVerificationCode(verificationCode))
               )

      result <- app.state.name match {
                  case State.PRE_PRODUCTION | State.PRODUCTION => verifyProduction(app)
                  case State.PENDING_REQUESTER_VERIFICATION    => verifyPending(app)
                  case _                                       => throw InvalidUpliftVerificationCode(verificationCode)
                }
    } yield result
  }

  private def insertStateHistory(
      snapshotApp: StoredApplication,
      newState: State,
      oldState: Option[State],
      actor: Actor,
      rollback: StoredApplication => Any
    ) = {
    val stateHistory = StateHistory(snapshotApp.id, newState, actor, oldState, changedAt = instant())

    stateHistoryRepository.insert(stateHistory) andThen {
      case Failure(_) =>
        rollback(snapshotApp)
    }
  }
}
