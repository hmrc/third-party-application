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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ValidatedApplicationName
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.{AbstractApplicationNamingService, ApplicationNamingService, AuditService}

@Singleton
class UpliftNamingService @Inject() (
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    nameValidationConfig: ApplicationNamingService.ApplicationNameValidationConfig
  )(implicit ec: ExecutionContext
  ) extends AbstractApplicationNamingService(
      auditService,
      applicationRepository,
      nameValidationConfig
    ) {

  import ApplicationNamingService._

  def upliftFilter(selfApplicationId: Option[ApplicationId]): ExclusionCondition =
    selfApplicationId.fold(noExclusions)(appId => excludeThisAppId(appId))

  def isDuplicateName(applicationName: String, selfApplicationId: Option[ApplicationId]): Future[Boolean] =
    isDuplicateName(applicationName, upliftFilter(selfApplicationId))

  def validateApplicationName(applicationName: String, selfApplicationId: Option[ApplicationId]): Future[OldApplicationNameValidationResult] = {
    ValidatedApplicationName(applicationName) match {
      case Some(validatedAppName) => validateApplicationName(validatedAppName, upliftFilter(selfApplicationId))
      case _                      => Future.successful(InvalidName)
    }
  }

  def assertAppHasUniqueNameAndAudit(submittedAppName: String, accessType: AccessType, existingApp: Option[StoredApplication] = None)(implicit hc: HeaderCarrier) = {

    for {
      duplicate <- isDuplicateName(submittedAppName, existingApp.map(_.id))
      _          =
        if (duplicate) {
          auditDeniedDueToNaming(submittedAppName, accessType, existingApp.map(_.id))
          throw ApplicationAlreadyExists(submittedAppName)
        } else { () }
    } yield ()
  }
}
