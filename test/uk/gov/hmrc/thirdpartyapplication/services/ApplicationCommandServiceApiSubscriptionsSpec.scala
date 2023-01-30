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

import scala.concurrent.Future

import cats.data.{NonEmptyChain, NonEmptyList, Validated}

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationCommandServiceUtils
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

class ApplicationCommandServiceApiSubscriptionsSpec extends ApplicationCommandServiceUtils with ApiIdentifierSyntax {

  trait Setup extends CommonSetup {
    ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
    NotificationRepositoryMock.ApplyEvents.succeeds()
    SubmissionsServiceMock.ApplyEvents.succeeds()
    StateHistoryRepoMock.ApplyEvents.succeeds()
    ApiPlatformEventServiceMock.ApplyEvents.succeeds
    ThirdPartyDelegatedAuthorityServiceMock.ApplyEvents.succeeds()
    ApiGatewayStoreMock.ApplyEvents.succeeds()
    NotificationServiceMock.SendNotifications.thenReturnSuccess()
    AuditServiceMock.ApplyEvents.succeeds()

    val applicationId                    = ApplicationId.random
    val applicationData: ApplicationData = anApplicationData(applicationId)

    val developer       = applicationData.collaborators.head
    val developerActor  = CollaboratorActor(developer.emailAddress)
    val gatekeeperActor = GatekeeperUserActor("admin@gatekeeper")

    val apiIdentifier = "some-context".asIdentifier("1.1")
    val timestamp     = FixedClock.now

    def testForSuccess(applicationUpdate: ApplicationCommand, event: UpdateApplicationEvent with UpdatesSubscription): Unit = {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.ApplyEvents.thenReturn(applicationData)
      SubscriptionRepoMock.ApplyEvents.succeeds()

      val result = await(underTest.update(applicationId, applicationUpdate).value)

      result shouldBe Right(applicationData)
      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      SubscriptionRepoMock.ApplyEvents.verifyCalledWith(event)
      ApiPlatformEventServiceMock.ApplyEvents.verifyCalledWith(NonEmptyList.one(event))
      AuditServiceMock.ApplyEvents.verifyCalledWith(applicationData, NonEmptyList.one(event))
    }

    def testForMissingApplication(applicationUpdate: ApplicationCommand): Unit = {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      val result = await(underTest.update(applicationId, applicationUpdate).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
      SubscriptionRepoMock.ApplyEvents.verifyNeverCalled
    }
  }

  "update with SubscribeToApi" should {

    "return the application if the application exists" in new Setup {
      val subscribeToApi = SubscribeToApi(developerActor, apiIdentifier, timestamp)
      val event          = ApiSubscribed(
        UpdateApplicationEvent.Id.random,
        applicationId,
        eventDateTime = timestamp,
        actor = developerActor,
        context = apiIdentifier.context.value,
        version = apiIdentifier.version.value
      )
      when(mockSubscribeToApiCommandHandler.process(*[ApplicationData], *[SubscribeToApi])(*)).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )

      testForSuccess(subscribeToApi, event)
    }

    "return the error if the application does not exist" in new Setup {
      testForMissingApplication(SubscribeToApi(developerActor, apiIdentifier, timestamp))
    }
  }

  "update with UnsubscribeFromApi" should {

    "return the application if the application exists" in new Setup {
      val unsubscribeFromApi = UnsubscribeFromApi(developerActor, apiIdentifier, timestamp)
      val event              = ApiUnsubscribed(
        UpdateApplicationEvent.Id.random,
        applicationId,
        eventDateTime = timestamp,
        actor = developerActor,
        context = apiIdentifier.context.value,
        version = apiIdentifier.version.value
      )
      when(mockUnsubscribeFromApiCommandHandler.process(*[ApplicationData], *[UnsubscribeFromApi])(*)).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )

      testForSuccess(unsubscribeFromApi, event)
    }

    "return the error if the application does not exist" in new Setup {
      testForMissingApplication(UnsubscribeFromApi(developerActor, apiIdentifier, timestamp))
    }
  }
}
