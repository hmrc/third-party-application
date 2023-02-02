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

import org.apache.commons.codec.binary.Base64.encodeBase64String
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class DeleteUnusedApplicationCommandHandlerSpec extends AsyncHmrcSpec with DeleteApplicationCommandHandlers {

  trait Setup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId: ApplicationId                 = ApplicationId.random
    val appAdminEmail: String                = loggedInUser
    val actor: ScheduledJobActor             = ScheduledJobActor("DeleteUnusedApplicationsJob")
    val app: ApplicationData                 = anApplicationData(appId, environment = Environment.SANDBOX)
    val authControlConfig: AuthControlConfig = AuthControlConfig(enabled = true, canDeleteApplications = true, "authorisationKey12345")

    val underTest = new DeleteUnusedApplicationCommandHandler(
      authControlConfig,
      ApplicationRepoMock.aMock,
      ApiGatewayStoreMock.aMock,
      NotificationRepositoryMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      ThirdPartyDelegatedAuthorityServiceMock.aMock,
      StateHistoryRepoMock.aMock
    )

    def checkSuccessResult()(result: CommandHandler2.CommandSuccess) = {
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
              requestingAdminEmail shouldBe actor.jobId
              requestingAdminName shouldBe actor.jobId
          }
        )
      }

    }
  }

  val reasons           = "reasons description text"
  val ts: LocalDateTime = FixedClock.now
  val authKey: String   = encodeBase64String("authorisationKey12345".getBytes(UTF_8))

  "DeleteUnusedApplicationCommand" should {
    val cmd = DeleteUnusedApplication("DeleteUnusedApplicationsJob", authKey, reasons, ts)
    "succeed as gkUserActor" in new Setup {
      ApplicationRepoMock.UpdateApplicationState.thenReturn(app)
      ApiGatewayStoreMock.ApplyEvents.succeeds()
      NotificationRepositoryMock.ApplyEvents.succeeds()
      ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
      ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
      StateHistoryRepoMock.ApplyEvents.succeeds()

      val result = await(underTest.process(app, cmd).value).right.value

      checkSuccessResult()(result)
    }

    "return an error when auth key doesnt match" in new Setup {
      val cmd = DeleteUnusedApplication("DeleteUnusedApplicationsJob", "notAuthKey", reasons, ts)

      val result = await(underTest.process(app, cmd).value).left.value.toNonEmptyList.toList

      result should have length 1
      result.head shouldBe "Cannot delete this applicaton"
    }
  }

}
