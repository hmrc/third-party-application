/*
 * Copyright 2020 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.mocks.repository

import java.util.UUID

import akka.japi.Option.Some
import org.mockito.captor.{ArgCaptor, Captor}
import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{APIIdentifier, ClientSecret, HasSucceeded, PaginatedApplicationData}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

trait ApplicationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseApplicationRepoMock {
    def aMock: ApplicationRepository

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock,mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object Fetch {
      def thenReturn(applicationData: ApplicationData) =
        when(aMock.fetch(eqTo(applicationData.id))).thenReturn(successful(Some(applicationData)))

      def thenReturnNone() =
        when(aMock.fetch(*)).thenReturn(successful(None))

      def thenReturnNoneWhen(applicationId: UUID) =
        when(aMock.fetch(eqTo(applicationId))).thenReturn(successful(None))

      def thenFail(failWith: Throwable) =
        when(aMock.fetch(*)).thenReturn(failed(failWith))

      def verifyCalledWith(applicationId: UUID) =
        ApplicationRepoMock.verify.fetch(eqTo(applicationId))

      def verifyFetch(): UUID = {
        val applicationDataArgumentCaptor = ArgCaptor[UUID]
        ApplicationRepoMock.verify.fetch(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor.value
      }
    }

    object FetchByClientId {
      def thenReturnWhen(clientId: String)(applicationData: ApplicationData) =
        when(aMock.fetchByClientId(clientId)).thenReturn(successful(Some(applicationData)))

      def thenReturnNone() =
        when(aMock.fetchByClientId(*)).thenReturn(successful(None))

      def thenReturnNoneWhen(clientId: String) =
        when(aMock.fetchByClientId(clientId)).thenReturn(successful(None))

      def thenFail(failWith: Throwable) =
        when(aMock.fetchByClientId(*)).thenReturn(failed(failWith))
    }

    object Save {
      private val defaultFn = (a :ApplicationData) => successful(a)
      def thenAnswer(fn: ApplicationData => Future[ApplicationData] = defaultFn) =
        when(aMock.save(*)).thenAnswer(fn)

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.save(*)).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.save(*)).thenReturn(failed(failWith))

      def verifyCalledWith(applicationData: ApplicationData) =
        ApplicationRepoMock.verify.save(eqTo(applicationData))

      def verifyNeverCalled() =
        ApplicationRepoMock.verify(never).save(*)

      def verifyCalled(): ApplicationData = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationData]
        ApplicationRepoMock.verify.save(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor.value
      }

      def verifyCalled(mode: VerificationMode): Captor[ApplicationData] = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationData]
        ApplicationRepoMock.verify(mode).save(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor
      }

      def verifyNothingSaved(): Unit = {
        verifyCalled(never)
      }
    }

    object FetchStandardNonTestingApps {
      def thenReturn(apps: ApplicationData*) =
        when(aMock.fetchStandardNonTestingApps()).thenReturn(successful(apps.toList))
    }

    object FetchByName {
      def thenReturnEmptyList() =
        when(aMock.fetchApplicationsByName(*)).thenReturn(successful(List.empty))

      def thenReturn(apps: ApplicationData*) =
        when(aMock.fetchApplicationsByName(*)).thenReturn(successful(apps.toList))

      def thenReturnWhen(name: String)(apps: ApplicationData*) =
        when(aMock.fetchApplicationsByName(eqTo(name))).thenReturn(successful(apps.toList))

      def thenReturnEmptyWhen(requestedName: String) = thenReturnWhen(requestedName)()

      def verifyCalledWith(duplicateName: String) =
        ApplicationRepoMock.verify.fetchApplicationsByName(eqTo(duplicateName))

      def veryNeverCalled() =
        ApplicationRepoMock.verify(never).fetchApplicationsByName(*)

    }

    object Delete {
      def verifyCalledWith(id: UUID) =
        ApplicationRepoMock.verify.delete(eqTo(id))

      def thenReturnHasSucceeded() =
        when(aMock.delete(*)).thenReturn(successful(HasSucceeded))
    }

    object FetchByServerToken {
      def thenReturnWhen(serverToken: String)(applicationData: ApplicationData) =
        when(aMock.fetchByServerToken(eqTo(serverToken))).thenReturn(successful(Some(applicationData)))

      def thenReturnNoneWhen(serverToken: String) =
      when(aMock.fetchByServerToken(eqTo(serverToken))).thenReturn(successful(None))

    }

    object FetchVerifiableUpliftBy {
      def thenReturnNoneWhen(verificationCode: String) =
        when(aMock.fetchVerifiableUpliftBy(eqTo(verificationCode))).thenReturn(successful(None))

      def thenReturnWhen(verificationCode: String)(applicationData: ApplicationData) =
        when(aMock.fetchVerifiableUpliftBy(eqTo(verificationCode))).thenReturn(successful(Some(applicationData)))

    }

    object FetchAllForContent {
      def thenReturnEmptyWhen(apiContext: String) =
      when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiContext: String)(apps: ApplicationData*) =
      when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(apps.toList))

    }

    object FetchAllForEmail {
      def thenReturnWhen(emailAddress: String)(apps: ApplicationData*) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(apps.toList))

      def thenReturnEmptyWhen(emailAddress: String) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(List.empty))
    }

    object FetchAllForApiIdentifier {
      def thenReturnEmptyWhen(apiIdentifier: APIIdentifier) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiIdentifier: APIIdentifier)(apps: ApplicationData*) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(apps.toList))
    }

    object FetchAllWithNoSubscriptions {
      def thenReturn(apps: ApplicationData*) =
      when(aMock.fetchAllWithNoSubscriptions()).thenReturn(successful(apps.toList))

      def thenReturnNone() =
        when(aMock.fetchAllWithNoSubscriptions()).thenReturn(successful(Nil))
    }

    object RecordApplicationUsage {
      def thenReturnWhen(applicationId: UUID)(applicationData: ApplicationData) =
        when(aMock.recordApplicationUsage(applicationId)).thenReturn(successful(applicationData))
    }

    object UpdateIpWhitelist {
      def verifyCalledWith(applicationId: UUID, newIpWhitelist: Set[String]) =
        ApplicationRepoMock.verify.updateApplicationIpWhitelist(eqTo(applicationId),eqTo(newIpWhitelist))

      def thenReturnWhen(applicationId: UUID, newIpWhitelist: Set[String])(updatedApplicationData: ApplicationData) =
        when(aMock.updateApplicationIpWhitelist(applicationId, newIpWhitelist)).thenReturn(successful(updatedApplicationData))
    }

    object SearchApplications {
      def thenReturn(data: PaginatedApplicationData) =
        when(aMock.searchApplications(*)).thenReturn(successful(data))
    }

    object ProcessAll {
      def thenReturn() = {
        when(aMock.processAll(*)).thenReturn(successful(()))
      }

      def verify() = {
        val captor = ArgCaptor[ApplicationData => Unit]
        ApplicationRepoMock.verify.processAll(captor)
        captor.value
      }
    }

    object RecordClientSecretUsage {
      def verifyNeverCalled() =
        ApplicationRepoMock.verify(never).recordClientSecretUsage(*,*)

      def thenReturnWhen(applicationId: String, clientSecret: String)(applicationData: ApplicationData) =
        when(aMock.recordClientSecretUsage(eqTo(applicationId),eqTo(clientSecret))).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.recordClientSecretUsage(*,*)).thenReturn(failed(failWith))
    }

    object UpdateApplicationRateLimit {
      def thenReturn(applicationId: UUID, rateLimit: RateLimitTier)(updatedApplication: ApplicationData) =
        when(aMock.updateApplicationRateLimit(applicationId, rateLimit)).thenReturn(successful(updatedApplication))

      def verifyCalledWith(applicationId: UUID, rateLimit: RateLimitTier) =
        ApplicationRepoMock.verify.updateApplicationRateLimit(eqTo(applicationId), eqTo(rateLimit))
    }

    object AddClientSecret {
      def thenReturn(applicationId: UUID, clientSecret: ClientSecret)(updatedApplication: ApplicationData) = {
        when(aMock.addClientSecret(applicationId, clientSecret)).thenReturn(successful(updatedApplication))
      }
    }

  }


  object ApplicationRepoMock extends BaseApplicationRepoMock {

    val aMock = mock[ApplicationRepository]

  }

  object LenientApplicationRepoMock extends BaseApplicationRepoMock {
    val aMock = mock[ApplicationRepository](withSettings.lenient())
  }
}
