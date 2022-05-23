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

package uk.gov.hmrc.thirdpartyapplication

import com.github.nscala_time.time.StaticDateTimeZone.UTC
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import java.time.{Instant, LocalDateTime, ZoneOffset, ZonedDateTime}

object Example  {
  def main(args: Array[String]): Unit = {

  import MongoJavatimeFormats.Implicits

    val json = """{
                 |      "id": "b16e9e3c-2aab-43c2-a484-9ea1b46015c2",
                 |      "name": "myApp-b16e9e3c-2aab-43c2-a484-9ea1b46015c2",
                 |      "normalisedName": "myapp-b16e9e3c-2aab-43c2-a484-9ea1b46015c2",
                 |      "collaborators": [
                 |        {
                 |          "emailAddress": "user@example.com",
                 |          "role": "ADMINISTRATOR",
                 |          "userId": "ff14699d-5cc5-4c22-8b99-633e4cad2f9a"
                 |        }
                 |      ],
                 |      "description": "description",
                 |      "wso2ApplicationName": "myapplication",
                 |      "tokens": {
                 |        "production": {
                 |          "clientId": "MEpAvhdC8bgztlJFjpw5xKXeKbCM",
                 |          "accessToken": "ccc",
                 |          "clientSecrets": []
                 |        }
                 |      },
                 |      "state": {
                 |        "name": "PRODUCTION",
                 |        "updatedOn": {
                 |          "$date": "2022-05-23T11:46:09.48Z"
                 |        }
                 |      },
                 |      "access": {
                 |        "redirectUris": [],
                 |        "overrides": [],
                 |        "accessType": "STANDARD"
                 |      },
                 |      "createdOn": {
                 |        "$date": "2019-07-08T00:00:00Z"
                 |      },
                 |      "lastAccess": {
                 |        "$date": "2019-07-10T00:00:00Z"
                 |      },
                 |      "rateLimitTier": "BRONZE",
                 |      "environment": "PRODUCTION",
                 |      "blocked": false,
                 |      "ipAllowlist": []
                 |}""".stripMargin

    val obj = Json.parse(json).validate[ApplicationData]
    println(obj)
  }

}
