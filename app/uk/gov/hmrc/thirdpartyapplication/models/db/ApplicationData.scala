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

package uk.gov.hmrc.thirdpartyapplication.models.db

import org.joda.time.DateTime
import uk.gov.hmrc.thirdpartyapplication.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.models.State.{PRODUCTION, TESTING}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.time.DateTimeUtils

case class ApplicationData(id: ApplicationId,
                           name: String,
                           normalisedName: String,
                           collaborators: Set[Collaborator],
                           description: Option[String] = None,
                           wso2ApplicationName: String,
                           tokens: ApplicationTokens,
                           state: ApplicationState,
                           access: Access = Standard(List.empty, None, None),
                           createdOn: DateTime,
                           lastAccess: Option[DateTime],
                           rateLimitTier: Option[RateLimitTier] = Some(BRONZE),
                           environment: String = Environment.PRODUCTION.toString,
                           checkInformation: Option[CheckInformation] = None,
                           blocked: Boolean = false,
                           ipWhitelist: Set[String] = Set.empty,
                           ipAllowlist: IpAllowlist = IpAllowlist()) {
  lazy val admins = collaborators.filter(_.role == Role.ADMINISTRATOR)
}

object ApplicationData {

  def create(application: CreateApplicationRequest, wso2ApplicationName: String, environmentToken: EnvironmentToken): ApplicationData = {

    val applicationState = (application.environment, application.access.accessType) match {
      case (Environment.SANDBOX, _) => ApplicationState(PRODUCTION)
      case (_, PRIVILEGED | ROPC) => ApplicationState(PRODUCTION, application.collaborators.headOption.map(_.emailAddress))
      case _ => ApplicationState(TESTING)
    }
    val createdOn = DateTimeUtils.now

    ApplicationData(
      ApplicationId.random,
      application.name,
      application.name.toLowerCase,
      application.collaborators,
      application.description,
      wso2ApplicationName,
      ApplicationTokens(environmentToken),
      applicationState,
      application.access,
      createdOn,
      Some(createdOn),
      environment = application.environment.toString)
  }
}

case class ApplicationTokens(production: EnvironmentToken)
