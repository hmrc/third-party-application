/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.data.{NonEmptyChain, NonEmptyList, Validated}
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.testutils.services.ApplicationUpdateServiceUtils

import java.time.LocalDateTime
import scala.concurrent.Future

class ApplicationUpdateServiceRedirectUrisSpec extends ApplicationUpdateServiceUtils with ApiIdentifierSyntax {

  trait Setup extends CommonSetup {
    StateHistoryRepoMock.ApplyEvents.succeeds()
    SubscriptionRepoMock.ApplyEvents.succeeds()
    SubmissionsServiceMock.ApplyEvents.succeeds()
    ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
    ApiPlatformEventServiceMock.ApplyEvents.succeeds
    AuditServiceMock.ApplyEvents.succeeds()
    NotificationServiceMock.SendNotifications.thenReturnSuccess()
    
    val applicationId = ApplicationId.random
    val applicationData: ApplicationData = anApplicationData(applicationId)

    val developer = applicationData.collaborators.head
    val developerActor = CollaboratorActor(developer.emailAddress)

    val oldRedirectUris = List.empty
    val newRedirectUris = List("https://new-url.example.com", "https://new-url.example.com/other-redirect")

    val timestamp = LocalDateTime.now
    val updateRedirectUris = UpdateRedirectUris(developerActor, oldRedirectUris, newRedirectUris, timestamp)
  }

  "update with UpdateRedirectUris" should {

    "return the application if the application exists" in new Setup {
      val event = RedirectUrisUpdated(
        UpdateApplicationEvent.Id.random,
        applicationId,
        eventDateTime = timestamp,
        actor = developerActor,
        oldRedirectUris = oldRedirectUris,
        newRedirectUris = newRedirectUris
      )
      when(mockUpdateRedirectUrisCommandHandler.process(*[ApplicationData], *[UpdateRedirectUris])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )

      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      ApplicationRepoMock.ApplyEvents.thenReturn(applicationData)
      
      val result = await(underTest.update(applicationId, updateRedirectUris).value)

      result shouldBe Right(applicationData)
      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      AuditServiceMock.ApplyEvents.verifyCalledWith(applicationData, NonEmptyList.one(event))
      ApiPlatformEventServiceMock.ApplyEvents.verifyCalledWith(NonEmptyList.one(event))
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)

      val result = await(underTest.update(applicationId, updateRedirectUris).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }

}
