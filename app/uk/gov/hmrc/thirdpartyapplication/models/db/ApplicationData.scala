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

package uk.gov.hmrc.thirdpartyapplication.models.db


import com.typesafe.config.ConfigFactory
import play.api.libs.json.{Format, Json, OFormat}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.{PRODUCTION, TESTING}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData.grantLengthConfig
import uk.gov.hmrc.thirdpartyapplication.repository.MongoJavaTimeFormats

import java.time.{LocalDateTime, ZoneOffset}

case class ApplicationTokens(production: Token)

object ApplicationTokens {
  implicit val dateFormat: Format[LocalDateTime] = MongoJavatimeFormats.localDateTimeFormat
  implicit val format: OFormat[ApplicationTokens] = Json.format[ApplicationTokens]
}

case class ApplicationData(
  id: ApplicationId,
  name: String,
  normalisedName: String,
  collaborators: Set[Collaborator],
  description: Option[String] = None,
  wso2ApplicationName: String,
  tokens: ApplicationTokens,
  state: ApplicationState,
  access: Access = Standard(),
  createdOn: LocalDateTime,
  lastAccess: Option[LocalDateTime],
  grantLength: Int = grantLengthConfig,
  rateLimitTier: Option[RateLimitTier] = Some(BRONZE),
  environment: String = Environment.PRODUCTION.toString,
  checkInformation: Option[CheckInformation] = None,
  blocked: Boolean = false,
  ipAllowlist: IpAllowlist = IpAllowlist()
) {
  lazy val admins = collaborators.filter(_.role == Role.ADMINISTRATOR)

  lazy val sellResellOrDistribute = access match {
    case Standard(_, _, _, _, sellResellOrDistribute, _) => sellResellOrDistribute
    case _ => None
  }

  def isInTesting = state.isInTesting
  def isPendingResponsibleIndividualVerification = state.isPendingResponsibleIndividualVerification
  def isPendingGatekeeperApproval = state.isPendingGatekeeperApproval
  def isPendingRequesterVerification = state.isPendingRequesterVerification
  def isInPreProductionOrProduction = state.isInPreProductionOrProduction
}

object ApplicationData {
//  val grantLengthConfig: Int = Some(ConfigFactory.load().getInt("grantLengthInDays")).getOrElse(547)
  val grantLengthConfig: Int = 547

  def create(createApplicationRequest: CreateApplicationRequest, wso2ApplicationName: String, environmentToken: Token, createdOn: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)): ApplicationData = {
    import createApplicationRequest._

    val applicationState = (environment, accessType) match {
      case (Environment.SANDBOX, _) => ApplicationState(PRODUCTION, updatedOn = createdOn)
      case (_, PRIVILEGED | ROPC) => ApplicationState(PRODUCTION, collaborators.headOption.map(_.emailAddress), updatedOn = createdOn)
      case _ => ApplicationState(TESTING, updatedOn = createdOn)
    }

    val checkInfo = createApplicationRequest match {
      case v1: CreateApplicationRequestV1 if(v1.anySubscriptions.nonEmpty) => Some(CheckInformation(apiSubscriptionsConfirmed = true))
      case v2: CreateApplicationRequestV2 => None
      case _ => None
    }

    val applicationAccess = createApplicationRequest match {
      case v1: CreateApplicationRequestV1 => v1.access
      case v2: CreateApplicationRequestV2 => Standard().copy(redirectUris = v2.access.redirectUris, overrides = v2.access.overrides, sellResellOrDistribute = Some(v2.upliftRequest.sellResellOrDistribute))
    }
    
    ApplicationData(
      ApplicationId.random,
      name,
      name.toLowerCase,
      collaborators,
      description,
      wso2ApplicationName,
      ApplicationTokens(environmentToken),
      applicationState,
      applicationAccess,
      createdOn,
      Some(createdOn),
      environment = environment.toString,
      checkInformation = checkInfo
    )
  }

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val dateFormat: Format[LocalDateTime] = MongoJavaTimeFormats.localDateTimeFormat

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
    (JsPath \ "createdOn").read[LocalDateTime] and
    (JsPath \\ "lastAccess").readNullable[LocalDateTime] and
    ((JsPath \ "grantLength").read[Int] or Reads.pure(grantLengthConfig) ) and
    (JsPath \ "rateLimitTier").readNullable[RateLimitTier] and
    (JsPath \ "environment").read[String] and
    (JsPath \ "checkInformation").readNullable[CheckInformation] and
    ((JsPath \ "blocked").read[Boolean] or Reads.pure(false)) and
    ((JsPath \ "ipAllowlist").read[IpAllowlist] or Reads.pure(IpAllowlist()))
  )(ApplicationData.apply _)

  implicit val format: Format[ApplicationData] = Format(applicationDataReads, Json.writes[ApplicationData])

}
