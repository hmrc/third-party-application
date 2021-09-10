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
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.{PRODUCTION, TESTING}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.time.DateTimeUtils
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import play.api.libs.json.Json


case class ApplicationTokens(production: Token)

object ApplicationTokens {
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val format = Json.format[ApplicationTokens]
}

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
                           ipAllowlist: IpAllowlist = IpAllowlist()) {
  lazy val admins = collaborators.filter(_.role == Role.ADMINISTRATOR)
}

object ApplicationData {

  def create(application: CreateApplicationRequest, wso2ApplicationName: String, environmentToken: Token): ApplicationData = {

    val applicationState = (application.environment, application.access.accessType) match {
      case (Environment.SANDBOX, _) => ApplicationState(PRODUCTION)
      case (_, PRIVILEGED | ROPC) => ApplicationState(PRODUCTION, application.collaborators.headOption.map(_.emailAddress))
      case _ => ApplicationState(TESTING)
    }
    val createdOn = DateTimeUtils.now

    val checkInfo = if(application.subscriptions.nonEmpty) Some(CheckInformation(apiSubscriptionsConfirmed = true)) else None

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
      environment = application.environment.toString,
      checkInformation = checkInfo)
  }

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  val applicationDataReads: Reads[ApplicationData] = (
    (JsPath \ "id").read[ApplicationId] and
    (JsPath \ "name").read[String] and
    (JsPath \ "normalisedName").read[String] and
    (JsPath \ "collaborators").read[Set[Collaborator]] and
    (JsPath \ "description").readNullable[String] and
    (JsPath \ "wso2ApplicationName").read[String] and
    (JsPath \ "tokens").read[ApplicationTokens] and
    (JsPath \ "state").read[ApplicationState] and
    (JsPath \ "access").read[Access] and
    (JsPath \ "createdOn").read[DateTime] and
    (JsPath \ "lastAccess").readNullable[DateTime] and
    (JsPath \ "rateLimitTier").readNullable[RateLimitTier] and
    (JsPath \ "environment").read[String] and
    (JsPath \ "checkInformation").readNullable[CheckInformation] and
    ((JsPath \ "blocked").read[Boolean] or Reads.pure(false)) and
    ((JsPath \ "ipAllowlist").read[IpAllowlist] or Reads.pure(IpAllowlist()))
  )(ApplicationData.apply _)

  implicit val format = OFormat(applicationDataReads, Json.writes[ApplicationData])

}
