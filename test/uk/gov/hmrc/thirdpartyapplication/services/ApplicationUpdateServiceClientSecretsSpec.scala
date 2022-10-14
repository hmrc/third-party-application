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

class ApplicationUpdateServiceClientSecretsSpec extends ApplicationUpdateServiceUtils {

  trait Setup extends CommonSetup {
    ResponsibleIndividualVerificationRepositoryMock.ApplyEvents.succeeds()
    SubmissionsServiceMock.ApplyEvents.succeeds()
    StateHistoryRepoMock.ApplyEvents.succeeds()
    ApiPlatformEventServiceMock.ApplyEvents.succeeds
    AuditServiceMock.ApplyEvents.succeeds
  }
    val timestamp = LocalDateTime.now
    val gatekeeperUser = "gkuser1"
    val adminName = "Mr Admin"
    val adminEmail = "admin@example.com"
    val applicationId = ApplicationId.random

    val applicationData: ApplicationData = anApplicationData(
      applicationId,
      access = Standard(importantSubmissionData = None)
    )

    val instigator = applicationData.collaborators.head.userId
  
    val clientSecret = ClientSecret("name", timestamp, hashedSecret = "hashed")
    val secretValue = "somSecret"

    val updatedProductionToken = productionToken.copy(clientSecrets = productionToken.clientSecrets ++ List(clientSecret))

  

  "update with AddClientSecret" should {
    
    val addClientSecret = AddClientSecret(instigator, adminEmail, secretValue, clientSecret, timestamp)
    
    "return the updated application if the application exists" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      val appAfter = applicationData.copy(tokens = ApplicationTokens(updatedProductionToken))
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      val event = ClientSecretAdded(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser), secretValue, clientSecret, adminEmail)

      when(mockAddClientSecretCommandHandler.process(*[ApplicationData], *[AddClientSecret])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )
      NotificationServiceMock.SendNotifications.thenReturnSuccess()

      val result = await(underTest.update(applicationId, addClientSecret).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      result shouldBe Right(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.verifyCalledWith(NonEmptyList.one(event))
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, addClientSecret).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }

  "update with RemoveClientSecret" should {
    
    val removeClientSecret = RemoveClientSecret(instigator, adminEmail, clientSecret.id, timestamp)

    "return the updated application if the application exists" in new Setup {
      ApplicationRepoMock.Fetch.thenReturn(applicationData)
      val appAfter = applicationData.copy(tokens = ApplicationTokens(updatedProductionToken))
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      val event = ClientSecretRemoved(
        UpdateApplicationEvent.Id.random,
        applicationId,
        LocalDateTime.now(),
        UpdateApplicationEvent.GatekeeperUserActor(gatekeeperUser),
        clientSecret.id,
        clientSecret.name,
        adminEmail)

      when(mockRemoveClientSecretCommandHandler.process(*[ApplicationData], *[RemoveClientSecret])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.of(event)).toValidatedNec)
      )
      NotificationServiceMock.SendNotifications.thenReturnSuccess()

      val result = await(underTest.update(applicationId, removeClientSecret).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(event)
      result shouldBe Right(appAfter)
      ApiPlatformEventServiceMock.ApplyEvents.verifyCalledWith(NonEmptyList.one(event))
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, removeClientSecret).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }
  }
}
