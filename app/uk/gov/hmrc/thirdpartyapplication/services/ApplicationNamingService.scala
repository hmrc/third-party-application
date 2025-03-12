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

import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ValidatedApplicationName
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.ApplicationNameValidationResult
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

object ApplicationNaming {
  type ExclusionCondition = (StoredApplication) => Boolean
  val noExclusions: ExclusionCondition                           = _ => false
  def excludeThisAppId(appId: ApplicationId): ExclusionCondition = (x: StoredApplication) => x.id == appId
}

object ApplicationNamingService {
  case class Config(nameDenyList: List[String], validateForDuplicateAppNames: Boolean)
}

abstract class AbstractApplicationNamingService(
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    nameValidationConfig: ApplicationNamingService.Config
  )(implicit ec: ExecutionContext
  ) {

  import ApplicationNaming._

  def isDuplicateName(applicationName: String, exclusions: ExclusionCondition): Future[Boolean] = {
    if (nameValidationConfig.validateForDuplicateAppNames) {
      applicationRepository
        .fetchApplicationsByName(applicationName)
        .map(_.filter(a => a.environment.isProduction)) // Helps with single db environments
        .map(_.filterNot(exclusions).nonEmpty)
    } else {
      successful(false)
    }
  }

  def isDenyListedName(applicationName: String): Boolean = {
    def checkNameIsValid(denyListedName: String) = !applicationName.toLowerCase().contains(denyListedName.toLowerCase)

    val isValid = nameValidationConfig
      .nameDenyList
      .forall(name => checkNameIsValid(name))

    !isValid
  }

  def validateApplicationNameWithExclusions(applicationName: ValidatedApplicationName, exclusions: ExclusionCondition): Future[ApplicationNameValidationResult] = {
    for {
      isDuplicate <- isDuplicateName(applicationName.value, exclusions)
      isDenyListed = isDenyListedName(applicationName.value)
    } yield (isDenyListed, isDuplicate) match {
      case (false, false) => ApplicationNameValidationResult.Valid
      case (true, _)      => ApplicationNameValidationResult.Invalid
      case (_, true)      => ApplicationNameValidationResult.Duplicate
    }
  }

  def auditDeniedDueToNaming(submittedAppName: String, accessType: AccessType, existingAppId: Option[ApplicationId])(implicit hc: HeaderCarrier): Future[AuditResult] =
    accessType match {
      case AccessType.PRIVILEGED => auditService.audit(CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName, Map("applicationName" -> submittedAppName))
      case AccessType.ROPC       => auditService.audit(CreateRopcApplicationRequestDeniedDueToNonUniqueName, Map("applicationName" -> submittedAppName))
      case _                     => auditService.audit(
          ApplicationUpliftRequestDeniedDueToNonUniqueName,
          existingAppId.map(id => AuditHelper.applicationId(id)).getOrElse(Map.empty) ++ Map("applicationName" -> submittedAppName)
        )
    }

  def auditDeniedDueToDenyListed(submittedAppName: String, accessType: AccessType, existingAppId: Option[ApplicationId])(implicit hc: HeaderCarrier): Future[AuditResult] =
    accessType match {
      case AccessType.PRIVILEGED => auditService.audit(CreatePrivilegedApplicationRequestDeniedDueToDenyListedName, Map("applicationName" -> submittedAppName))
      case AccessType.ROPC       => auditService.audit(CreateRopcApplicationRequestDeniedDueToDenyListedName, Map("applicationName" -> submittedAppName))
      case _                     => auditService.audit(
          ApplicationUpliftRequestDeniedDueToDenyListedName,
          existingAppId.map(id => AuditHelper.applicationId(id)).getOrElse(Map.empty) ++ Map("applicationName" -> submittedAppName)
        )
    }
}
