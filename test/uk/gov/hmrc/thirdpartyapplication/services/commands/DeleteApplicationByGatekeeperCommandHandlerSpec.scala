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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}

class DeleteApplicationByGatekeeperCommandHandlerSpec extends AsyncHmrcSpec with DeleteApplicationCommandHandlers {

  trait Setup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId                                = ApplicationId.random
    val reasons                              = "reasons description text"
    val app                                  = anApplicationData(appId, environment = Environment.SANDBOX)
    val ts                                   = FixedClock.now
    val authControlConfig: AuthControlConfig = AuthControlConfig(enabled = true, canDeleteApplications = true, "authorisationKey12345")

    val underTest = new DeleteApplicationByGatekeeperCommandHandler(
      authControlConfig,
      ApplicationRepoMock.aMock,
      ApiGatewayStoreMock.aMock,
      NotificationRepositoryMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      ThirdPartyDelegatedAuthorityServiceMock.aMock,
      StateHistoryRepoMock.aMock
    )

    def checkSuccessResult()(result: CommandHandler.CommandSuccess) = {
      inside(result) { case (app, events) =>
        val filteredEvents = events.toList.filter(evt =>
          evt match {
            case _: ApplicationStateChanged | _: ApplicationDeletedByGatekeeper => true
            case _                                                              => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ApplicationDeletedByGatekeeper(_, appId, eventDateTime, actor, clientId, wsoApplicationName, evtReasons, requestingAdminEmail) =>
              appId shouldBe appId
              actor shouldBe actor
              eventDateTime shouldBe ts
              clientId shouldBe app.tokens.production.clientId
              evtReasons shouldBe reasons
              wsoApplicationName shouldBe app.wso2ApplicationName
              requestingAdminEmail shouldBe requestedByEmail

            case ApplicationStateChanged(_, appId, eventDateTime, evtActor, oldAppState, newAppState, requestingAdminName, requestingAdminEmail) =>
              appId shouldBe appId
              evtActor shouldBe actor
              eventDateTime shouldBe ts
              oldAppState shouldBe app.state.name
              newAppState shouldBe State.DELETED
              requestingAdminEmail shouldBe requestedByEmail
              requestingAdminName shouldBe requestedByEmail
          }
        )
      }

    }
  }

  val gatekeeperUser    = "gatekeeperuser"
  val actor             = GatekeeperUserActor(gatekeeperUser)
  val reasons           = "reasons description text"
  val ts: LocalDateTime = FixedClock.now

  "DeleteApplicationByGatekeeper" should {
    val cmd = DeleteApplicationByGatekeeper(gatekeeperUser, requestedByEmail, reasons, ts)
    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.UpdateApplicationState.thenReturn(app)
      StateHistoryRepoMock.ApplyEvents.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()

      val result = await(underTest.process(app, cmd).value).right.value

      checkSuccessResult()(result)
    }

  }

}
