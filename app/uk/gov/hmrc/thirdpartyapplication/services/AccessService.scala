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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.NotFoundException

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, AccessType, OverrideFlag}
import uk.gov.hmrc.thirdpartyapplication.controllers.{OverridesResponse, ScopeResponse}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class AccessService @Inject() (applicationRepository: ApplicationRepository, auditService: AuditService)(implicit val ec: ExecutionContext) {

  def readScopes(applicationId: ApplicationId): Future[ScopeResponse] =
    fetchApp(applicationId) map getScopes map ScopeResponse

  def readOverrides(applicationId: ApplicationId): Future[OverridesResponse] =
    fetchApp(applicationId) map getOverrides map OverridesResponse

  private def fetchApp(applicationId: ApplicationId): Future[StoredApplication] =
    applicationRepository.fetch(applicationId).flatMap {
      case Some(applicationData) => successful(applicationData)
      case None                  => failed(new NotFoundException(s"application not found for id: ${applicationId}"))
    }

  private def getPrivilegedAccess(applicationData: StoredApplication): Access.Privileged =
    applicationData.access.asInstanceOf[Access.Privileged]

  private def getRopcAccess(applicationData: StoredApplication): Access.Ropc =
    applicationData.access.asInstanceOf[Access.Ropc]

  private def getScopes(applicationData: StoredApplication): Set[String] =
    privilegedOrRopc[Set[String]](
      applicationData, {
        getPrivilegedAccess(_).scopes
      }, {
        getRopcAccess(_).scopes
      }
    )

  private def privilegedOrRopc[T](applicationData: StoredApplication, privilegedFunction: StoredApplication => T, ropcFunction: StoredApplication => T) =
    applicationData.access.accessType match {
      case AccessType.PRIVILEGED => privilegedFunction(applicationData)
      case AccessType.ROPC       => ropcFunction(applicationData)
      case _: AccessType         => throw new RuntimeException("Standard App found unexpectedly in privilegedOrRopc()")
    }

  private def getStandardAccess(applicationData: StoredApplication): Access.Standard =
    applicationData.access.asInstanceOf[Access.Standard]

  private def getOverrides(applicationData: StoredApplication): Set[OverrideFlag] =
    getStandardAccess(applicationData).overrides
}
