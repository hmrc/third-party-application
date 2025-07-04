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

package uk.gov.hmrc.thirdpartyapplication.services.commands.delete

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationStateFixtures, State}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeleteApplicationByGatekeeper
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec, DeleteApplicationCommandHandlers}

class DeleteApplicationByGatekeeperCommandHandlerSpec extends CommandHandlerBaseSpec with ApplicationStateFixtures {

  val requestedByName  = "john smith"
  val requestedByEmail = "john.smith@example.com".toLaxEmail
  val reasons          = "reasons description text"
  val ts: Instant      = instant

  trait Setup extends DeleteApplicationCommandHandlers {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val app                                  = storedApp.inSandbox()
    val authControlConfig: AuthControlConfig = AuthControlConfig(enabled = true, canDeleteApplications = true, "authorisationKey12345")

    val underTest = new DeleteApplicationByGatekeeperCommandHandler(
      authControlConfig,
      ApplicationRepoMock.aMock,
      ApiGatewayStoreMock.aMock,
      NotificationRepositoryMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      ThirdPartyDelegatedAuthorityServiceMock.aMock,
      StateHistoryRepoMock.aMock,
      TermsOfUseInvitationRepositoryMock.aMock
    )

    def checkSuccessResult()(result: CommandHandler.Success) = {
      inside(result) { case (returnedApp, events) =>
        val filteredEvents = events.toList.filter(evt =>
          evt match {
            case _: ApplicationEvents.ApplicationStateChanged | _: ApplicationEvents.ApplicationDeletedByGatekeeper => true
            case _                                                                                                  => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ApplicationEvents.ApplicationDeletedByGatekeeper(_, appId, eventDateTime, actor, clientId, wsoApplicationName, evtReasons, requestingAdminEmail) =>
              appId shouldBe app.id
              actor shouldBe actor
              eventDateTime shouldBe ts
              clientId shouldBe app.tokens.production.clientId
              evtReasons shouldBe reasons
              wsoApplicationName shouldBe app.wso2ApplicationName
              requestingAdminEmail shouldBe requestedByEmail

            case ApplicationEvents.ApplicationStateChanged(_, appId, eventDateTime, evtActor, oldAppState, newAppState, requestingAdminName, requestingAdminEmail) =>
              appId shouldBe app.id
              evtActor shouldBe actor
              eventDateTime shouldBe ts
              oldAppState shouldBe app.state.name.toString()
              newAppState shouldBe State.DELETED.toString()
              requestingAdminEmail shouldBe requestedByEmail
              requestingAdminName shouldBe requestedByEmail.text
          }
        )
      }
    }
  }

  val actor = gatekeeperActor

  "DeleteApplicationByGatekeeper" should {
    val cmd = DeleteApplicationByGatekeeper(gatekeeperUser, requestedByEmail, reasons, ts)

    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.UpdateApplicationState.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
      ResponsibleIndividualVerificationRepositoryMock.DeleteAllByApplicationId.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.RevokeApplicationAuthorities.succeeds()
      NotificationRepositoryMock.DeleteAllByApplicationId.thenReturnSuccess()
      TermsOfUseInvitationRepositoryMock.Delete.thenReturn()

      val result = await(underTest.process(app, cmd).value).value

      checkSuccessResult()(result)
    }

  }

}
