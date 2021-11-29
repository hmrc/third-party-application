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

package uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import javax.inject.Inject
import javax.inject.Singleton

import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, Valid, Invalid}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.models._

case class ApplicationNameValidationConfig(nameBlackList: List[String], validateForDuplicateAppNames: Boolean)

@Singleton
class ApplicationNamingService @Inject()(
  auditService: AuditService,
  applicationRepository: ApplicationRepository,
  nameValidationConfig: ApplicationNameValidationConfig
)(implicit ec: ExecutionContext) {

  def isDuplicateName(applicationName: String, thisApplicationId: Option[ApplicationId]): Future[Boolean] = {

    def isThisApplication(app: ApplicationData) = thisApplicationId.contains(app.id)

    def anyDuplicatesExcludingThis(apps: List[ApplicationData]): Boolean = {
      apps.exists(!isThisApplication(_))
    }

    if (nameValidationConfig.validateForDuplicateAppNames) {
      applicationRepository.fetchApplicationsByName(applicationName)
        .map(anyDuplicatesExcludingThis)
    } else {
      successful(false)
    }
  }

  def isBlacklistedName(applicationName: String): Boolean = {
    def checkNameIsValid(blackListedName: String) = !applicationName.toLowerCase().contains(blackListedName.toLowerCase)

    val isValid = nameValidationConfig
      .nameBlackList
      .forall(name => checkNameIsValid(name))

    !isValid
  }

  def validateApplicationName(applicationName: String, selfApplicationId: Option[ApplicationId]) : Future[ApplicationNameValidationResult] = {
    for {
      isDuplicate   <- isDuplicateName(applicationName, selfApplicationId)
      isBlacklisted =  isBlacklistedName(applicationName)
    } yield (isBlacklisted, isDuplicate) match {
      case (false, false) => Valid
      case (blacklist, duplicate) => Invalid(blacklist, duplicate)
    }
  }

  def assertAppHasUniqueNameAndAudit(
    submittedAppName: String,
    accessType: AccessType,
    existingApp: Option[ApplicationData] = None
  )(implicit hc: HeaderCarrier) = {
    
    for {
      duplicate <- isDuplicateName(submittedAppName, existingApp.map(_.id))
      _ = if (duplicate) {
        accessType match {
          case PRIVILEGED => auditService.audit(CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName,
            Map("applicationName" -> submittedAppName))
          case ROPC => auditService.audit(CreateRopcApplicationRequestDeniedDueToNonUniqueName,
            Map("applicationName" -> submittedAppName))
          case _ => auditService.audit(ApplicationUpliftRequestDeniedDueToNonUniqueName,
            AuditHelper.applicationId(existingApp.get.id) ++ Map("applicationName" -> submittedAppName))
        }
        throw ApplicationAlreadyExists(submittedAppName)
      }
    } yield ()
  }
}
