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

package uk.gov.hmrc.thirdpartyapplication.util

import com.github.t3hnar.bcrypt._
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Environment.Environment
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier


import scala.collection.mutable.Map
import uk.gov.hmrc.thirdpartyapplication.domain.models._

trait ApplicationTestData extends ApplicationStateUtil {

  val idsByEmail = Map[String, UserId]()
  def idOf(email: String) = {
    idsByEmail.getOrElseUpdate(email, UserId.random)
  } 

  def aSecret(secret: String): ClientSecret = ClientSecret(secret.takeRight(4), hashedSecret = secret.bcrypt(4))

  val loggedInUser = "loggedin@example.com"
  val devEmail = "dev@example.com"

  val serverTokenLastAccess = HmrcTime.now
  val productionToken = Token(ClientId("aaa"), "bbb", List(aSecret("secret1"), aSecret("secret2")), Some(serverTokenLastAccess))

  
  val requestedByEmail = "john.smith@example.com"
  val grantLength = 547

  def anApplicationData(applicationId: ApplicationId,
                                state: ApplicationState = productionState(requestedByEmail),
                                collaborators: Set[Collaborator] = Set(Collaborator(loggedInUser, Role.ADMINISTRATOR, idOf(loggedInUser))),
                                access: Access = Standard(),
                                rateLimitTier: Option[RateLimitTier] = Some(RateLimitTier.BRONZE),
                                environment: Environment = Environment.PRODUCTION,
                                ipAllowlist: IpAllowlist = IpAllowlist(),
                                grantLength: Int = grantLength) = {
    ApplicationData(
      applicationId,
      "MyApp",
      "myapp",
      collaborators,
      Some("description"),
      "aaaaaaaaaa",
      ApplicationTokens(productionToken),
      state,
      access,
      HmrcTime.now,
      Some(HmrcTime.now),
      grantLength,
      rateLimitTier = rateLimitTier,
      environment = environment.toString,
      ipAllowlist = ipAllowlist)
  }

}