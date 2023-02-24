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

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}

class DeleteProductionCredentialsApplicationCommandHandlerSpec extends AsyncHmrcSpec with DeleteApplicationCommandHandlers {

  trait Setup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId                                = ApplicationId.random
    val appAdminEmail                        = loggedInUser
    val jobId                                = "DeleteUnusedApplicationsJob"
    val actor                                = Actors.ScheduledJob(jobId)
    val reasons                              = "reasons description text"
    val app                                  = anApplicationData(appId, environment = Environment.SANDBOX, state = ApplicationState.testing)
    val ts                                   = FixedClock.instant
    val authControlConfig: AuthControlConfig = AuthControlConfig(enabled = true, canDeleteApplications = true, "authorisationKey12345")

    val underTest = new DeleteProductionCredentialsApplicationCommandHandler(
      authControlConfig,
      ApplicationRepoMock.aMock,
      ApiGatewayStoreMock.aMock,
      NotificationRepositoryMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      ThirdPartyDelegatedAuthorityServiceMock.aMock,
      StateHistoryRepoMock.aMock
    )

    def checkSuccessResult()(result: CommandHandler.Success) = {
      inside(result) { case (app, events) =>
        val filteredEvents = events.toList.filter(evt =>
          evt match {
            case _: ApplicationStateChanged | _: ProductionCredentialsApplicationDeleted => true
            case _                                                                       => false
          }
        )
        filteredEvents.size shouldBe 2

        filteredEvents.foreach(event =>
          inside(event) {
            case ProductionCredentialsApplicationDeleted(_, appId, eventDateTime, actor, clientId, wsoApplicationName, evtReasons) =>
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
              oldAppState shouldBe app.state.name.toString()
              newAppState shouldBe State.DELETED.toString()
              requestingAdminEmail.text shouldBe actor.jobId
              requestingAdminName shouldBe actor.jobId
          }
        )
      }
    }
    
    def checkFailsWith(msg: String, msgs: String*)(fn: => CommandHandler.ResultT) = {
      val testThis = await(fn.value).left.value.toNonEmptyList.toList

      testThis should have length 1 + msgs.length
      testThis.head shouldBe CommandFailures.GenericFailure(msg)
      testThis.tail shouldBe msgs.map(CommandFailures.GenericFailure(_))
    }
  }

  val reasons           = "reasons description text"
  val ts: LocalDateTime = FixedClock.now

  "DeleteProductionCredentialsApplication" should {
    val cmd = DeleteProductionCredentialsApplication("DeleteUnusedApplicationsJob", reasons, ts)
    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.UpdateApplicationState.thenReturn(app)
      StateHistoryRepoMock.Insert.succeeds()
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()

      val result = await(underTest.process(app, cmd).value).right.value

      checkSuccessResult()(result)
    }

    "return an error when app is NOT in testing state" in new Setup {
      val cmd = DeleteProductionCredentialsApplication("DeleteUnusedApplicationsJob", reasons, FixedClock.now)

      checkFailsWith("App is not in TESTING state") {
        underTest.process(app.copy(state = app.state.copy(name = State.PRE_PRODUCTION)), cmd)
      }
    }
  }

}
