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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

object ApplicationNamingService {
  type ExclusionCondition = (ApplicationData) => Boolean
  def noExclusions: ExclusionCondition                           = _ => false
  def excludeThisAppId(appId: ApplicationId): ExclusionCondition = (x: ApplicationData) => x.id == appId

  case class ApplicationNameValidationConfig(nameDenyList: List[String], validateForDuplicateAppNames: Boolean)
}

abstract class AbstractApplicationNamingService(
    auditService: AuditService,
    applicationRepository: ApplicationRepository,
    nameValidationConfig: ApplicationNamingService.ApplicationNameValidationConfig
  )(implicit ec: ExecutionContext
  ) {

  import ApplicationNamingService._

  def isDuplicateName(applicationName: String, exclusions: ExclusionCondition): Future[Boolean] = {
    if (nameValidationConfig.validateForDuplicateAppNames) {
      applicationRepository
        .fetchApplicationsByName(applicationName)
        .map(_.filter(a => Environment.apply(a.environment) == Some(Environment.PRODUCTION))) // Helps with single db environments
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

  def validateApplicationName(applicationName: String, exclusions: ExclusionCondition): Future[ApplicationNameValidationResult] = {
    for {
      isDuplicate <- isDuplicateName(applicationName, exclusions)
      isDenyListed = isDenyListedName(applicationName)
    } yield (isDenyListed, isDuplicate) match {
      case (false, false) => ValidName
      case (true, _)      => InvalidName
      case (_, true)      => DuplicateName
    }
  }

  def auditDeniedDueToNaming(submittedAppName: String, accessType: AccessType, existingAppId: Option[ApplicationId])(implicit hc: HeaderCarrier): Future[AuditResult] =
    accessType match {
      case PRIVILEGED => auditService.audit(CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName, Map("applicationName" -> submittedAppName))
      case ROPC       => auditService.audit(CreateRopcApplicationRequestDeniedDueToNonUniqueName, Map("applicationName" -> submittedAppName))
      case _          => auditService.audit(
          ApplicationUpliftRequestDeniedDueToNonUniqueName,
          existingAppId.map(id => AuditHelper.applicationId(id)).getOrElse(Map.empty) ++ Map("applicationName" -> submittedAppName)
        )
    }

  def auditDeniedDueToDenyListed(submittedAppName: String, accessType: AccessType, existingAppId: Option[ApplicationId])(implicit hc: HeaderCarrier): Future[AuditResult] =
    accessType match {
      case PRIVILEGED => auditService.audit(CreatePrivilegedApplicationRequestDeniedDueToDenyListedName, Map("applicationName" -> submittedAppName))
      case ROPC       => auditService.audit(CreateRopcApplicationRequestDeniedDueToDenyListedName, Map("applicationName" -> submittedAppName))
      case _          => auditService.audit(
          ApplicationUpliftRequestDeniedDueToDenyListedName,
          existingAppId.map(id => AuditHelper.applicationId(id)).getOrElse(Map.empty) ++ Map("applicationName" -> submittedAppName)
        )
    }
}
