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

package uk.gov.hmrc.thirdpartyapplication.services

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful

import org.apache.pekko.actor.ActorSystem

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationStateFixtures
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class ThirdPartyDelegatedAuthorityServiceSpec extends AsyncHmrcSpec with ApplicationStateFixtures {

  implicit val actorSystem: ActorSystem = ActorSystem("test")

  trait Setup {
    implicit val hc: HeaderCarrier                                                       = HeaderCarrier()
    val mockThirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector = mock[ThirdPartyDelegatedAuthorityConnector]
    val underTest                                                                        = new ThirdPartyDelegatedAuthorityService(mockThirdPartyDelegatedAuthorityConnector)
  }

  "ThirdPartyDelegatedAuthorityService" should {
    "handle an ApplicationDeleted event by calling the connector" in new Setup {
      val clientId = ClientId.random

      when(mockThirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(clientId)(hc)).thenReturn(successful(HasSucceeded))

      val result = await(underTest.revokeApplicationAuthorities(clientId))

      result shouldBe Some(HasSucceeded)
      verify(mockThirdPartyDelegatedAuthorityConnector).revokeApplicationAuthorities(clientId)(hc)
    }
  }
}
