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

import java.time.{LocalDateTime, ZoneOffset}

import play.api.libs.json.{JsBoolean, JsObject, Json}

import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

trait TestRawApplicationDocuments {

  private def dateToJsonObj(date: LocalDateTime) = Json.obj(f"$$date" -> date.toInstant(ZoneOffset.UTC).toEpochMilli)

  import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository.MongoFormats._

  def applicationToMongoJson(application: StoredApplication, allowAutoDelete: Option[Boolean] = None): JsObject = {
    val applicationJson = Json.obj(
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
      "grantLength"         -> 547,
      "rateLimitTier"       -> "BRONZE",
      "environment"         -> "PRODUCTION",
      "blocked"             -> false,
      "ipAllowlist"         -> application.ipAllowlist
    )

    allowAutoDelete match {
      case Some(value: Boolean) =>
        applicationJson + ("allowAutoDelete" -> JsBoolean(value))
      case None                 => applicationJson
    }
  }
}
