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

package uk.gov.hmrc.thirdpartyapplication.repository.mongo

import java.time.Instant
import java.time.temporal.ChronoUnit

import play.api.libs.json.{JsNumber, JsObject, Json}

import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

trait TestRawApplicationDocuments {

  private def dateToJsonObj(date: Instant) = Json.obj(f"$$date" -> date.truncatedTo(ChronoUnit.MILLIS).toEpochMilli)

  import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository.MongoFormats._

  def applicationToMongoJson(
      application: StoredApplication,
      grantLength: Option[Int] = Some(547),
      refreshTokensAvailableFor: Boolean = true
    ): JsObject = {

    def addAttributes(json: JsObject): JsObject = {
      (grantLength, refreshTokensAvailableFor) match {
        case (Some(gl: Int), true)  =>
          json ++ Json.obj("grantLength" -> JsNumber(gl), "refreshTokensAvailableFor" -> Json.toJson(application.refreshTokensAvailableFor))
        case (Some(gl: Int), false) =>
          json ++ Json.obj("grantLength" -> JsNumber(gl))
        case (None, true)           =>
          json ++ Json.obj("refreshTokensAvailableFor" -> Json.toJson(application.refreshTokensAvailableFor))
        case (Some(gl: Int), true)  =>
          json ++ Json.obj(
            "grantLength"               -> JsNumber(gl),
            "refreshTokensAvailableFor" -> Json.toJson(application.refreshTokensAvailableFor)
          )
        case (None, true)           =>
          json ++ Json.obj("refreshTokensAvailableFor" -> Json.toJson(application.refreshTokensAvailableFor))
        case (Some(gl: Int), false) =>
          json ++ Json.obj("grantLength" -> JsNumber(gl))
        case (None, false)          => json
      }
    }

    val applicationJson: JsObject = Json.obj(
      "id"                  -> application.id,
      "name"                -> application.name,
      "normalisedName"      -> application.normalisedName,
      "collaborators"       -> application.collaborators,
      "description"         -> application.description,
      "wso2ApplicationName" -> application.wso2ApplicationName,
      "tokens"              -> application.tokens,
      "state"               -> application.state,
      "access"              -> application.access,
      "createdOn"           -> dateToJsonObj(application.createdOn),
      "lastAccess"          -> dateToJsonObj(application.createdOn),
      "rateLimitTier"       -> "BRONZE",
      "environment"         -> "PRODUCTION",
      "blocked"             -> false,
      "ipAllowlist"         -> application.ipAllowlist
    )

    addAttributes(applicationJson)
  }
}
