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

package uk.gov.hmrc.thirdpartyapplication.modules.approvals.services

import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._

import javax.inject.Inject
import javax.inject.Singleton

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.services.AuditService
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService
import uk.gov.hmrc.thirdpartyapplication.services.AbstractApplicationNamingService

@Singleton
class ApprovalsApplicationNamingService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  nameValidationConfig: ApplicationNamingService.ApplicationNameValidationConfig
)(implicit ec: ExecutionContext) 
    extends AbstractApplicationNamingService(auditService, applicationRepository, nameValidationConfig) {

  import ApplicationNamingService._

  val excludeInTesting: ExclusionCondition = (x: ApplicationData) => x.state.name == State.TESTING
  def or(a: ExclusionCondition, b:ExclusionCondition):ExclusionCondition = (x:ApplicationData) => a(x) || b(x)

  def approvalsFilter(appId: ApplicationId): ExclusionCondition = or( excludeThisAppId(appId), excludeInTesting)

  def isDuplicateName(applicationName: String, appId: ApplicationId): Future[Boolean] = 
    isDuplicateName(applicationName, approvalsFilter(appId) )

  def validateApplicationName(applicationName: String, appId: ApplicationId) : Future[ApplicationNameValidationResult] =
    validateApplicationName(applicationName, approvalsFilter(appId) )
    
  def assertAppHasUniqueNameAndAudit(
    submittedAppName: String,
    accessType: AccessType,
    existingApp: ApplicationData
  )(implicit hc: HeaderCarrier) = {
    
    for {
      duplicate <- isDuplicateName(submittedAppName, existingApp.id)
      _ = if (duplicate) {
            auditDeniedDueToNaming(submittedAppName, accessType, Some(existingApp.id))
            throw ApplicationAlreadyExists(submittedAppName)
          } else { Unit }
    } yield ()
  }
}