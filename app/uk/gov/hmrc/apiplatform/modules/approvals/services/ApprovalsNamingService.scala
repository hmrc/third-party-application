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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ValidatedApplicationName
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.ApplicationNameValidationResult
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.{AbstractApplicationNamingService, ApplicationNaming, ApplicationNamingService, AuditService}

@Singleton
class ApprovalsNamingService @Inject() (
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    nameValidationConfig: ApplicationNamingService.Config
  )(implicit ec: ExecutionContext
  ) extends AbstractApplicationNamingService(auditService, applicationRepository, nameValidationConfig) {

  import ApplicationNaming._

  private val excludeInTesting: ExclusionCondition                                 = (x: StoredApplication) => x.isInTesting
  private def or(a: ExclusionCondition, b: ExclusionCondition): ExclusionCondition = (x: StoredApplication) => a(x) || b(x)

  private def approvalsFilter(appId: ApplicationId): ExclusionCondition = or(excludeThisAppId(appId), excludeInTesting)

  def validateApplicationName(applicationName: ValidatedApplicationName, appId: ApplicationId): Future[ApplicationNameValidationResult] =
    validateApplicationNameWithExclusions(applicationName, approvalsFilter(appId))

  def validateApplicationNameAndAudit(applicationName: ValidatedApplicationName, appId: ApplicationId, accessType: AccessType)(implicit hc: HeaderCarrier)
      : Future[ApplicationNameValidationResult] =
    for {
      validationResult <- validateApplicationNameWithExclusions(applicationName, approvalsFilter(appId))
      _                <- validationResult match {
                            case ApplicationNameValidationResult.Valid     => successful(())
                            case ApplicationNameValidationResult.Duplicate => auditDeniedDueToNaming(applicationName.value, accessType, Some(appId))
                            case ApplicationNameValidationResult.Invalid   => auditDeniedDueToDenyListed(applicationName.value, accessType, Some(appId))
                          }
    } yield validationResult
}
