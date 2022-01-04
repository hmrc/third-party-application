/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.controllers.{OverridesRequest, OverridesResponse, ScopeRequest, ScopeResponse}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.{PRIVILEGED, ROPC}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction.{OverrideAdded, OverrideRemoved, ScopeAdded, ScopeRemoved}

import scala.concurrent.Future.{failed, sequence, successful}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AccessService @Inject()(applicationRepository: ApplicationRepository, auditService: AuditService)(implicit val ec: ExecutionContext) {

  def readScopes(applicationId: ApplicationId): Future[ScopeResponse] =
    fetchApp(applicationId) map getScopes map ScopeResponse

  def updateScopes(applicationId: ApplicationId, scopeRequest: ScopeRequest)
                  (implicit headerCarrier: HeaderCarrier): Future[ScopeResponse] = {

    def updateWithScopes(applicationData: ApplicationData, newScopes: Set[String]): ApplicationData = {
      val updatedAccess = privilegedOrRopc[Access](applicationData, {
        getPrivilegedAccess(_).copy(scopes = newScopes)
      }, {
        getRopcAccess(_).copy(scopes = newScopes)
      })
      applicationData.copy(access = updatedAccess)
    }

    for {
      originalApplicationData <- fetchApp(applicationId)
      oldScopes = getScopes(originalApplicationData)
      newScopes = scopeRequest.scopes
      persistedApplicationData <- applicationRepository.save(updateWithScopes(originalApplicationData, newScopes))
      _ = sequence(oldScopes diff newScopes map ScopeRemoved.details map (auditService.audit(ScopeRemoved, _)))
      _ = sequence(newScopes diff oldScopes map ScopeAdded.details map (auditService.audit(ScopeAdded, _)))
    } yield ScopeResponse(getScopes(persistedApplicationData))
  }

  def readOverrides(applicationId: ApplicationId): Future[OverridesResponse] =
    fetchApp(applicationId) map getOverrides map OverridesResponse

  def updateOverrides(applicationId: ApplicationId, overridesRequest: OverridesRequest)
                     (implicit headerCarrier: HeaderCarrier): Future[OverridesResponse] = {

    def updateWithOverrides(applicationData: ApplicationData, newOverrides: Set[OverrideFlag]): ApplicationData =
      applicationData.copy(access = getStandardAccess(applicationData).copy(overrides = newOverrides))

    for {
      originalApplicationData <- fetchApp(applicationId)
      oldOverrides = getOverrides(originalApplicationData)
      newOverrides = overridesRequest.overrides
      persistedApplicationData <- applicationRepository.save(updateWithOverrides(originalApplicationData, newOverrides))
      _ = sequence(oldOverrides diff newOverrides map OverrideRemoved.details map (auditService.audit(OverrideRemoved, _)))
      _ = sequence(newOverrides diff oldOverrides map OverrideAdded.details map (auditService.audit(OverrideAdded, _)))
    } yield OverridesResponse(getOverrides(persistedApplicationData))
  }

  private def fetchApp(applicationId: ApplicationId): Future[ApplicationData] =
    applicationRepository.fetch(applicationId).flatMap {
      case Some(applicationData) => successful(applicationData)
      case None => failed(new NotFoundException(s"application not found for id: ${applicationId.value}"))
    }

  private def getPrivilegedAccess(applicationData: ApplicationData): Privileged =
    applicationData.access.asInstanceOf[Privileged]

  private def getRopcAccess(applicationData: ApplicationData): Ropc =
    applicationData.access.asInstanceOf[Ropc]

  private def getScopes(applicationData: ApplicationData): Set[String] =
    privilegedOrRopc[Set[String]](applicationData, {
      getPrivilegedAccess(_).scopes
    }, {
      getRopcAccess(_).scopes
    })

  private def privilegedOrRopc[T](applicationData: ApplicationData, privilegedFunction: ApplicationData => T, ropcFunction: ApplicationData => T) =
    AccessType.withName(applicationData.access.accessType.toString) match {
      case PRIVILEGED => privilegedFunction(applicationData)
      case ROPC => ropcFunction(applicationData)
    }

  private def getStandardAccess(applicationData: ApplicationData): Standard =
    applicationData.access.asInstanceOf[Standard]

  private def getOverrides(applicationData: ApplicationData): Set[OverrideFlag] =
    getStandardAccess(applicationData).overrides
}
