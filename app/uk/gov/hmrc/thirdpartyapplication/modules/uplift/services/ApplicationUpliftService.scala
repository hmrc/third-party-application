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

import javax.inject.Inject
import javax.inject.Singleton

import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import scala.util.Failure
import uk.gov.hmrc.thirdpartyapplication.services.AuditService
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService
import uk.gov.hmrc.thirdpartyapplication.services.AuditHelper

@Singleton
class ApplicationUpliftService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  stateHistoryRepository: StateHistoryRepository,
  applicationNamingService: ApplicationNamingService
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
