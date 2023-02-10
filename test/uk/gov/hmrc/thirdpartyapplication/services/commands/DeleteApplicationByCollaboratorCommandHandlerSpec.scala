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
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborators.Roles

class DeleteApplicationByCollaboratorCommandHandlerSpec extends AsyncHmrcSpec with DeleteApplicationCommandHandlers {

//
  trait Setup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId = ApplicationId.random

    val appAdminEmail = "admin@example.com"
    val reasons       = "reasons description text"
    val actor         = Actors.AppCollaborator(appAdminEmail)

    val app               = anApplicationData(appId, environment = Environment.SANDBOX).copy(collaborators =
      Set(
        Collaborator(appAdminEmail, Roles.ADMINISTRATOR, appAdminUserId)
      )
    )
    val ts                = FixedClock.now
    val authControlConfig = AuthControlConfig(true, true, "authorisationKey12345")

    val underTest = new DeleteApplicationByCollaboratorCommandHandler(
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
            case _: ApplicationStateChanged | _: ApplicationDeleted => true
            case _                                                  => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ApplicationDeleted(_, appId, eventDateTime, actor, clientId, wsoApplicationName, evtReasons) =>
              appId shouldBe appId
              actor shouldBe actor
              eventDateTime shouldBe ts
              clientId shouldBe app.tokens.production.clientId
              evtReasons shouldBe reasons
              wsoApplicationName shouldBe app.wso2ApplicationName

            case ApplicationStateChanged(_, appId, eventDateTime, evtActor, oldAppState, newAppState, requestingAdminName, requestingAdminEmail) =>
              appId shouldBe appId
              evtActor shouldBe actor
              eventDateTime shouldBe ts
              oldAppState shouldBe app.state.name
              newAppState shouldBe State.DELETED
              requestingAdminEmail shouldBe actor.email
              requestingAdminName shouldBe actor.email
          }
        )
      }

    }
  }
  val appAdminUserId    = UserId.random
  val reasons           = "reasons description text"
  val ts: LocalDateTime = FixedClock.now

  "DeleteApplicationByCollaborator" should {
    val cmd = DeleteApplicationByCollaborator(appAdminUserId, reasons, ts)
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

    "return an error when app is NOT in testing state" in new Setup {
      val nonStandardApp = app.copy(access = Ropc(Set.empty))
      val cmd            = DeleteApplicationByCollaborator(appAdminUserId, reasons, ts)

      val result = await(underTest.process(nonStandardApp, cmd).value).left.value.toNonEmptyList.toList

      result should have length 1
      result.head shouldBe "App must have a STANDARD access type"
    }

    "return an error if the actor is not an admin of the application" in new Setup {
      val result = await(underTest.process(app, DeleteApplicationByCollaborator(UserId.random, reasons, ts)).value).left.value.toNonEmptyList.toList
      result.head shouldBe "User must be an ADMIN"
    }

  }

}
