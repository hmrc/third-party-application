/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers.query

import uk.gov.hmrc.apiplatform.modules.common.domain.models._

sealed trait Param[+P] {
  def order: Int
  def paramName: String
}

object Param {
  case class ServerTokenQP(value: String)           extends Param[String] { val order = 1; val paramName = "serverToken"     }
  case class ClientIdQP(value: ClientId)            extends Param[ClientId]      { val order = 2; val paramName = "clientId"      }
  case class UserAgentQP(value: String)             extends Param[String] { val order = 3; val paramName = "userAgent"       }
  case class ApplicationIdQP(value: ApplicationId)  extends Param[ApplicationId] { val order = 4; val paramName = "applicationId" }
  
  case object NoSubscriptionsQP                 extends Param[Unit]          { val order = 10; val paramName = "noSubscriptions" }
  case class ApiContextQP(value: ApiContext)       extends Param[ApiContext]    { val order = 11; val paramName = "context"         }
  case class ApiVersionNbrQP(value: ApiVersionNbr) extends Param[ApiVersionNbr] { val order = 12; val paramName = "versionNbr"      }

  case class UserIdQP(value: UserId)           extends Param[UserId]      { val order = 20; val paramName = "userId"      }
  case class EnvironmentQP(value: Environment) extends Param[Environment] { val order = 21; val paramName = "environment" }

  case class PageSizeQP(value: Int) extends Param[Int] { val order = 100; val paramName = "pageSize" }
  case class PageNbrQP(value: Int)  extends Param[Int] { val order = 101; val paramName = "pageNbr"  }
}
