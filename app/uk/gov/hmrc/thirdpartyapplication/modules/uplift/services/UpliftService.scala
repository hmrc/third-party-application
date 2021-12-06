/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.uplift.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, _}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.modules.uplift.domain.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{ApiGatewayStore, AuditHelper, AuditService}
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationLogger

@Singleton
class UpliftService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  stateHistoryRepository: StateHistoryRepository,
  applicationNamingService: UpliftNamingService,
  apiGatewayStore: ApiGatewayStore
)(implicit ec: ExecutionContext)
  extends ApplicationLogger {

  def requestUplift(applicationId: ApplicationId,
                    applicationName: String,
                    requestedByEmailAddress: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {

    def uplift(existing: ApplicationData) = existing.copy(
      name = applicationName,
      normalisedName = applicationName.toLowerCase,
      state = existing.state.toPendingGatekeeperApproval(requestedByEmailAddress))

    for {
      app         <- fetchApp(applicationId)
      upliftedApp =  uplift(app)
      _           <- applicationNamingService.assertAppHasUniqueNameAndAudit(applicationName, app.access.accessType, Some(app))
      updatedApp  <- applicationRepository.save(upliftedApp)
      _           <- insertStateHistory(
        app,
        PENDING_GATEKEEPER_APPROVAL, Some(TESTING),
        requestedByEmailAddress, COLLABORATOR,
        (a: ApplicationData) => applicationRepository.save(a)
      )
      _ = logger.info(s"UPLIFT01: uplift request (pending) application:${app.name} appId:${app.id} appState:${app.state.name} " +
        s"appRequestedByEmailAddress:${app.state.requestedByEmailAddress}")
      _ = auditService.audit(ApplicationUpliftRequested,
        AuditHelper.applicationId(applicationId) ++ AuditHelper.calculateAppNameChange(app, updatedApp))
    } yield UpliftRequested
  }
  
  
  def verifyUplift(verificationCode: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {

    def verifyProduction(app: ApplicationData) = {
      logger.info(s"Application uplift for '${app.name}' has been verified already. No update was executed.")
      successful(UpliftVerified)
    }

    def findLatestUpliftRequester(applicationId: ApplicationId): Future[String] = for {
      history <- stateHistoryRepository.fetchLatestByStateForApplication(applicationId, State.PENDING_GATEKEEPER_APPROVAL)
      state = history.getOrElse(throw new RuntimeException(s"Pending state not found for application: ${applicationId.value}"))
    } yield state.actor.id

    def audit(app: ApplicationData) =
      findLatestUpliftRequester(app.id) flatMap { email =>
        auditService.audit(ApplicationUpliftVerified, AuditHelper.applicationId(app.id), Map("upliftRequestedByEmail" -> email))
      }

    def verifyPending(app: ApplicationData) = for {
      _ <- apiGatewayStore.createApplication(app.wso2ApplicationName, app.tokens.production.accessToken)
      _ <- applicationRepository.save(app.copy(state = app.state.toProduction))
      _ <- insertStateHistory(app, State.PRODUCTION, Some(PENDING_REQUESTER_VERIFICATION),
        app.state.requestedByEmailAddress.get, COLLABORATOR, (a: ApplicationData) => applicationRepository.save(a))
      _ = logger.info(s"UPLIFT02: Application uplift for application:${app.name} appId:${app.id} has been verified successfully")
      _ = audit(app)
    } yield UpliftVerified

    for {
      app <- applicationRepository.fetchVerifiableUpliftBy(verificationCode)
        .map(_.getOrElse(throw InvalidUpliftVerificationCode(verificationCode)))

      result <- app.state.name match {
        case State.PRODUCTION => verifyProduction(app)
        case PENDING_REQUESTER_VERIFICATION => verifyPending(app)
        case _ => throw InvalidUpliftVerificationCode(verificationCode)
      }
    } yield result
  }

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType, rollback: ApplicationData => Any) = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState)
    
    stateHistoryRepository.insert(stateHistory) andThen {
      case Failure(_) =>
        rollback(snapshotApp)
    }
  }

  private def fetchApp(applicationId: ApplicationId): Future[ApplicationData] = {
    val notFoundException = new NotFoundException(s"application not found for id: ${applicationId.value}")
    
    applicationRepository.fetch(applicationId).flatMap {
      case None => failed(notFoundException)
      case Some(app) => successful(app)
    }
  }
}
