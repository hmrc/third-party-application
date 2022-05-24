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

package uk.gov.hmrc.thirdpartyapplication.util

import java.time.LocalDate


object Example {
  def main(args: Array[String]): Unit = {

    val json = """ {
                 |         "id":"17626696-1511-4278-b33c-b96ce7b8afb2",
                 |         "name":"myApp-17626696-1511-4278-b33c-b96ce7b8afb2",
                 |         "normalisedName":"myapp-17626696-1511-4278-b33c-b96ce7b8afb2",
                 |         "collaborators":[
                 |            {
                 |               "emailAddress":"user@example.com",
                 |               "role":"ADMINISTRATOR",
                 |               "userId":"da3f8772-18e9-4965-8666-d4bf7cc9ddec"
                 |            }
                 case class Example|         ],
                 |         "description":"description",
                 |         "wso2ApplicationName":"myapplication",
                 |         "tokens":{
                 |            "production":{
                 |               "clientId":"gmvbkp94v9Ah74yvqY2r7yOFvig9",
                 |               "accessToken":"ccc",
                 |               "clientSecrets":[
                 |                  
                 |               ]
                 |            }
                 |         },
                 |         "state":{
                 |            "name":"PRODUCTION",
                 |            "updatedOn":{
                 |               "$date":"2022-05-24T09:11:40.916Z"
                 |            }
                 |         },
                 |         "access":{
                 |            "redirectUris":[
                 |               
                 |            ],
                 |            "overrides":[
                 |               
                 |            ],
                 |            "accessType":"STANDARD"
                 |         },
                 |         "createdOn":{
                 |            "$date":"2019-07-08T00:00:00Z"
                 |         },
                 |         "lastAccess":{
                 |            "$date":"2019-07-10T00:00:00Z"
                 |         },
                 |         "rateLimitTier":"BRONZE",
                 |         "environment":"PRODUCTION"
                 |      }""".stripMargin

    /*val obj = Json.parse(json).validate[ApplicationData]
    println(obj)*/

    val dt = LocalDate.parse("2022-12-01")
    println(s"LocalDate: $dt" )
  }

}
