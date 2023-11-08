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

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeleteApplicationByCollaborator
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, Environment, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access

class DeleteApplicationByCollaboratorCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends DeleteApplicationCommandHandlers {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId = ApplicationId.random

    val appAdminEmail = "admin@example.com".toLaxEmail
    val reasons       = "reasons description text"
    val actor         = Actors.AppCollaborator(appAdminEmail)

    val app               = anApplicationData(appId, environment = Environment.SANDBOX).copy(collaborators =
      Set(
        appAdminEmail.admin(appAdminUserId)
      )
    )
    val ts                = FixedClock.instant
    val authControlConfig = AuthControlConfig(true, true, "authorisationKey12345")

    val underTest = new DeleteApplicationByCollaboratorCommandHandler(
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
      inside(result) { case (app, events) =>
        val filteredEvents = events.toList.filter(evt =>
          evt match {
            case _: ApplicationEvents.ApplicationStateChanged | _: ApplicationEvents.ApplicationDeleted => true
            case _                                                                                      => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ApplicationEvents.ApplicationDeleted(_, appId, eventDateTime, actor, clientId, wsoApplicationName, evtReasons) =>
              appId shouldBe appId
              actor shouldBe actor
              eventDateTime shouldBe ts
              clientId shouldBe app.tokens.production.clientId
              evtReasons shouldBe reasons
              wsoApplicationName shouldBe app.wso2ApplicationName

            case ApplicationEvents.ApplicationStateChanged(_, appId, eventDateTime, evtActor, oldAppState, newAppState, requestingAdminName, requestingAdminEmail) =>
              appId shouldBe appId
              evtActor shouldBe actor
              eventDateTime shouldBe ts
              oldAppState shouldBe app.state.name.toString()
              newAppState shouldBe State.DELETED.toString()
              requestingAdminEmail shouldBe actor.email
              requestingAdminName shouldBe actor.email.text
          }
        )
      }
    }
  }

  val appAdminUserId = UserId.random
  val reasons        = "reasons description text"
  val ts             = FixedClock.instant

  "DeleteApplicationByCollaborator" should {
    val cmd = DeleteApplicationByCollaborator(appAdminUserId, reasons, now)
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

    "return an error when app is NOT in testing state" in new Setup {
      val nonStandardApp = app.copy(access = Access.Ropc(Set.empty))
      val cmd            = DeleteApplicationByCollaborator(appAdminUserId, reasons, now)

      checkFailsWith("App must have a STANDARD access type") {
        underTest.process(nonStandardApp, cmd)
      }
    }

    "return an error if the actor is not an admin of the application" in new Setup {
      checkFailsWith("User must be an ADMIN") {
        underTest.process(app, DeleteApplicationByCollaborator(UserId.random, reasons, now))
      }
    }

  }

}
