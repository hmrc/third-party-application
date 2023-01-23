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

import akka.actor.ActorSystem
import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ApplicationDeleted, CollaboratorActor}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

class ThirdPartyDelegatedAuthorityServiceSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  implicit val actorSystem: ActorSystem = ActorSystem("test")

  trait Setup {
    implicit val hc: HeaderCarrier                                                       = HeaderCarrier()
    val mockThirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector = mock[ThirdPartyDelegatedAuthorityConnector]
    val underTest                                                                        = new ThirdPartyDelegatedAuthorityService(mockThirdPartyDelegatedAuthorityConnector)
  }

  "applyEvents" should {
    val now                                                        = FixedClock.now
    val clientId                                                   = ClientId("clientId")
    def buildApplicationDeletedEvent(applicationId: ApplicationId) =
      ApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        now,
        CollaboratorActor("requester@example.com"),
        clientId,
        "wso2ApplicationName",
        "reasons"
      )

    "handle an ApplicationDeleted event by calling the connector" in new Setup {
      val applicationId1 = ApplicationId.random

      when(mockThirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(clientId)(hc)).thenReturn(successful(HasSucceeded))

      val event = buildApplicationDeletedEvent(applicationId1)

      val result = await(underTest.applyEvents(NonEmptyList.one(event)))

      result shouldBe Some(HasSucceeded)
      verify(mockThirdPartyDelegatedAuthorityConnector).revokeApplicationAuthorities(clientId)(hc)
    }
  }
}
