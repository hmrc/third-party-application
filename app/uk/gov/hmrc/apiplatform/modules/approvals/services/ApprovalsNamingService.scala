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
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.{AbstractApplicationNamingService, ApplicationNamingService, AuditService}

@Singleton
class ApprovalsNamingService @Inject() (
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    nameValidationConfig: ApplicationNamingService.ApplicationNameValidationConfig
  )(implicit ec: ExecutionContext
  ) extends AbstractApplicationNamingService(auditService, applicationRepository, nameValidationConfig) {

  import ApplicationNamingService._

  private val excludeInTesting: ExclusionCondition                                 = (x: ApplicationData) => x.isInTesting
  private def or(a: ExclusionCondition, b: ExclusionCondition): ExclusionCondition = (x: ApplicationData) => a(x) || b(x)

  private def approvalsFilter(appId: ApplicationId): ExclusionCondition = or(excludeThisAppId(appId), excludeInTesting)

  def validateApplicationName(applicationName: String, appId: ApplicationId): Future[ApplicationNameValidationResult] =
    validateApplicationName(applicationName, approvalsFilter(appId))

  def validateApplicationNameAndAudit(applicationName: String, appId: ApplicationId, accessType: AccessType)(implicit hc: HeaderCarrier): Future[ApplicationNameValidationResult] =
    for {
      validationResult <- validateApplicationName(applicationName, approvalsFilter(appId))
      _                <- validationResult match {
                            case ValidName     => successful(())
                            case DuplicateName => auditDeniedDueToNaming(applicationName, accessType, Some(appId))
                            case InvalidName   => auditDeniedDueToDenyListed(applicationName, accessType, Some(appId))
                          }
    } yield validationResult
}
