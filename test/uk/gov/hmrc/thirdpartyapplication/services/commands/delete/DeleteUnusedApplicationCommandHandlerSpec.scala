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

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.commons.codec.binary.Base64.encodeBase64String

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.DeleteUnusedApplication
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.commands.{CommandHandler, CommandHandlerBaseSpec, DeleteApplicationCommandHandlers}

class DeleteUnusedApplicationCommandHandlerSpec extends CommandHandlerBaseSpec {

  trait Setup extends DeleteApplicationCommandHandlers {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appAdminEmail                        = adminTwo.emailAddress
    val actor: Actors.ScheduledJob           = Actors.ScheduledJob("DeleteUnusedApplicationsJob")
    val app: StoredApplication               = storedApp.inSandbox()
    val authControlConfig: AuthControlConfig = AuthControlConfig(enabled = true, canDeleteApplications = true, "authorisationKey12345")

    val underTest = new DeleteUnusedApplicationCommandHandler(
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
            case _: ApplicationEvents.ApplicationStateChanged | _: ApplicationEvents.ApplicationDeleted => true
            case _                                                                                      => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ApplicationEvents.ApplicationDeleted(_, appId, eventDateTime, actor, clientId, wsoApplicationName, evtReasons) =>
              appId shouldBe app.id
              actor shouldBe actor
              eventDateTime shouldBe ts
              clientId shouldBe app.tokens.production.clientId
              evtReasons shouldBe reasons
              wsoApplicationName shouldBe app.wso2ApplicationName

            case ApplicationEvents.ApplicationStateChanged(_, appId, eventDateTime, evtActor, oldAppState, newAppState, requestingAdminName, requestingAdminEmail) =>
              appId shouldBe app.id
              evtActor shouldBe actor
              eventDateTime shouldBe ts
              oldAppState shouldBe app.state.name.toString()
              newAppState shouldBe State.DELETED.toString()
              requestingAdminEmail.text shouldBe actor.jobId.toLowerCase
              requestingAdminName shouldBe actor.jobId
          }
        )
      }
    }
  }

  val reasons         = "reasons description text"
  val ts              = FixedClock.instant
  val authKey: String = encodeBase64String("authorisationKey12345".getBytes(UTF_8))

  "DeleteUnusedApplicationCommand" should {
    val cmd = DeleteUnusedApplication("DeleteUnusedApplicationsJob", authKey, reasons, instant)
    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.UpdateApplicationState.thenReturn(app)
      ApiGatewayStoreMock.DeleteApplication.thenReturnHasSucceeded()
      ResponsibleIndividualVerificationRepositoryMock.DeleteAllByApplicationId.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.RevokeApplicationAuthorities.succeeds()
      NotificationRepositoryMock.DeleteAllByApplicationId.thenReturnSuccess()
      StateHistoryRepoMock.Insert.succeeds()
      TermsOfUseInvitationRepositoryMock.Delete.thenReturn()

      val result = await(underTest.process(app, cmd).value).value

      checkSuccessResult()(result)
    }

    "return an error when auth key doesnt match" in new Setup {
      val cmd = DeleteUnusedApplication("DeleteUnusedApplicationsJob", "notAuthKey", reasons, instant)

      checkFailsWith("Cannot delete this application") {
        underTest.process(app, cmd)
      }
    }
  }

}
