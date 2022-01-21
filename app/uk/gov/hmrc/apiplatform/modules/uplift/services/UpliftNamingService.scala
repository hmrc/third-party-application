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

package uk.gov.hmrc.apiplatform.modules.uplift.services


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
import uk.gov.hmrc.thirdpartyapplication.services.AuditService
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationNamingService
import uk.gov.hmrc.thirdpartyapplication.services.AbstractApplicationNamingService

@Singleton
class UpliftNamingService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  nameValidationConfig: ApplicationNamingService.ApplicationNameValidationConfig
)(implicit ec: ExecutionContext) 
    extends AbstractApplicationNamingService(auditService, applicationRepository, nameValidationConfig) {

  import ApplicationNamingService._

  val excludeNothing: ExclusionCondition = (x: ApplicationData) => false

  def upliftFilter(selfApplicationId: Option[ApplicationId]): ExclusionCondition = 
    selfApplicationId.fold(excludeNothing)(appId => excludeThisAppId(appId))

  def isDuplicateName(applicationName: String, selfApplicationId: Option[ApplicationId]): Future[Boolean] =
    isDuplicateName(applicationName, upliftFilter(selfApplicationId))

  def validateApplicationName(applicationName: String, selfApplicationId: Option[ApplicationId]) : Future[ApplicationNameValidationResult] =
     validateApplicationName(applicationName, upliftFilter(selfApplicationId))

  def assertAppHasUniqueNameAndAudit(
    submittedAppName: String,
    accessType: AccessType,
    existingApp: Option[ApplicationData] = None
  )(implicit hc: HeaderCarrier) = {
    
    for {
      duplicate <- isDuplicateName(submittedAppName, existingApp.map(_.id))
      _ = if (duplicate) {
            auditDeniedDueToNaming(submittedAppName, accessType, existingApp.map(_.id))
            throw ApplicationAlreadyExists(submittedAppName)
          } else { Unit }
    } yield ()
  }
}
