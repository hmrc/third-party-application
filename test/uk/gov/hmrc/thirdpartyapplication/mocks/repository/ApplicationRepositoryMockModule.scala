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

package uk.gov.hmrc.thirdpartyapplication.mocks.repository

import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

import org.mockito.captor.{ArgCaptor, Captor}
import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.http.NotFoundException

import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientId, Collaborator, PrivacyPolicyLocation, TermsAndConditionsLocation}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.SubmissionId
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

trait ApplicationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApplicationRepoMock {
    def aMock: ApplicationRepository

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object Fetch {

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.fetch(eqTo(applicationData.id))).thenReturn(successful(Some(applicationData)))

      def thenReturnNone() =
        when(aMock.fetch(*[ApplicationId])).thenReturn(successful(None))

      def thenReturnNoneWhen(applicationId: ApplicationId) =
        when(aMock.fetch(eqTo(applicationId))).thenReturn(successful(None))

      def thenFail(failWith: Throwable) =
        when(aMock.fetch(*[ApplicationId])).thenReturn(failed(failWith))

      def verifyCalledWith(applicationId: ApplicationId) =
        ApplicationRepoMock.verify.fetch(eqTo(applicationId))

      def verifyFetch(): ApplicationId = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationId]
        ApplicationRepoMock.verify.fetch(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor.value
      }
    }

    object GetSubscriptionsForDeveloper {

      def thenReturnWhen(userId: UserId)(apis: Set[ApiIdentifier]) =
        when(aMock.getSubscriptionsForDeveloper(eqTo(userId))).thenReturn(successful(apis))

      def thenFailWith(ex: Exception) =
        when(aMock.getSubscriptionsForDeveloper(*[UserId])).thenReturn(failed(ex))
    }

    object FetchByClientId {

      def thenReturnWhen(clientId: ClientId)(applicationData: ApplicationData) =
        when(aMock.fetchByClientId(eqTo(clientId))).thenReturn(successful(Some(applicationData)))

      def thenReturnNone() =
        when(aMock.fetchByClientId(*[ClientId])).thenReturn(successful(None))

      def thenReturnNoneWhen(clientId: ClientId) =
        when(aMock.fetchByClientId(eqTo(clientId))).thenReturn(successful(None))

      def thenFail(failWith: Throwable) =
        when(aMock.fetchByClientId(*[ClientId])).thenReturn(failed(failWith))
    }

    object Save {
      private val defaultFn = (a: ApplicationData) => successful(a)

      def thenAnswer(fn: ApplicationData => Future[ApplicationData] = defaultFn) =
        when(aMock.save(*)).thenAnswer(fn)

      def thenAnswer() =
        when(aMock.save(*)).thenAnswer((app: ApplicationData) => successful(app))

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.save(*)).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.save(*)).thenReturn(failed(failWith))

      def verifyCalledWith(applicationData: ApplicationData) =
        verify.save(eqTo(applicationData))

      def verifyNeverCalled() =
        verify(never).save(*)

      def verifyCalled(): ApplicationData = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationData]
        verify.save(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor.value
      }

      def verifyCalled(mode: VerificationMode): Captor[ApplicationData] = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationData]
        verify(mode).save(applicationDataArgumentCaptor)
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

      def verifyCalledWith(id: ApplicationId) =
        ApplicationRepoMock.verify.delete(eqTo(id), any)

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.delete(*[ApplicationId], *)).thenReturn(successful(applicationData))
    }

    object HardDelete {

      def verifyCalledWith(id: ApplicationId) =
        ApplicationRepoMock.verify.hardDelete(eqTo(id))

      def thenReturnHasSucceeded() =
        when(aMock.hardDelete(*[ApplicationId])).thenReturn(successful(HasSucceeded))
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

      def thenReturnEmptyWhen(apiContext: ApiContext) =
        when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiContext: ApiContext)(apps: ApplicationData*) =
        when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(apps.toList))

    }

    object FetchAllForEmail {

      def thenReturnWhen(emailAddress: String)(apps: ApplicationData*) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(apps.toList))

      def thenReturnEmptyWhen(emailAddress: String) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(List.empty))
    }

    object fetchAllForUserId {

      def thenReturnWhen(userId: UserId, includeDeleted: Boolean)(apps: ApplicationData*) =
        when(aMock.fetchAllForUserId(eqTo(userId), eqTo(includeDeleted))).thenReturn(successful(apps.toList))
    }

    object FetchAllForApiIdentifier {

      def thenReturnEmptyWhen(apiIdentifier: ApiIdentifier) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiIdentifier: ApiIdentifier)(apps: ApplicationData*) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(apps.toList))
    }

    object FetchAllWithNoSubscriptions {

      def thenReturn(apps: ApplicationData*) =
        when(aMock.fetchAllWithNoSubscriptions()).thenReturn(successful(apps.toList))

      def thenReturnNone() =
        when(aMock.fetchAllWithNoSubscriptions()).thenReturn(successful(Nil))
    }

    object GetAppsWithSubscriptions {

      def thenReturn(apps: ApplicationWithSubscriptions*) =
        when(aMock.getAppsWithSubscriptions).thenReturn(successful(apps.toList))

      def thenReturnNone() =
        when(aMock.getAppsWithSubscriptions).thenReturn(successful(Nil))
    }

    object RecordApplicationUsage {

      def thenReturnWhen(applicationId: ApplicationId)(applicationData: ApplicationData) =
        when(aMock.recordApplicationUsage(eqTo(applicationId))).thenReturn(successful(applicationData))
    }

    object RecordServerTokenUsage {

      def thenReturnWhen(applicationId: ApplicationId)(applicationData: ApplicationData) =
        when(aMock.recordServerTokenUsage(eqTo(applicationId))).thenReturn(successful(applicationData))

      def verifyCalledWith(applicationId: ApplicationId) =
        ApplicationRepoMock.verify.recordServerTokenUsage(eqTo(applicationId))
    }

    object UpdateIpAllowlist {

      def verifyCalledWith(applicationId: ApplicationId, newIpAllowlist: IpAllowlist) =
        ApplicationRepoMock.verify.updateApplicationIpAllowlist(eqTo(applicationId), eqTo(newIpAllowlist))

      def thenReturnWhen(applicationId: ApplicationId, newIpAllowlist: IpAllowlist)(updatedApplicationData: ApplicationData) =
        when(aMock.updateApplicationIpAllowlist(eqTo(applicationId), eqTo(newIpAllowlist))).thenReturn(successful(updatedApplicationData))
    }

    object UpdateGrantLength {

      def verifyCalledWith(applicationId: ApplicationId, newGrantLength: Int) =
        ApplicationRepoMock.verify.updateApplicationGrantLength(eqTo(applicationId), eqTo(newGrantLength))

      def thenReturnWhen(applicationId: ApplicationId, newGrantLength: Int)(updatedApplicationData: ApplicationData) =
        when(aMock.updateApplicationGrantLength(eqTo(applicationId), eqTo(newGrantLength))).thenReturn(successful(updatedApplicationData))
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
        ApplicationRepoMock.verify(never).recordClientSecretUsage(*[ApplicationId], *)

      def thenReturnWhen(applicationId: ApplicationId, clientSecretId: String)(applicationData: ApplicationData) =
        when(aMock.recordClientSecretUsage(eqTo(applicationId), eqTo(clientSecretId))).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.recordClientSecretUsage(*[ApplicationId], *)).thenReturn(failed(failWith))
    }

    object UpdateApplicationRateLimit {

      def thenReturn(applicationId: ApplicationId, rateLimit: RateLimitTier)(updatedApplication: ApplicationData) =
        when(aMock.updateApplicationRateLimit(eqTo(applicationId), eqTo(rateLimit))).thenReturn(successful(updatedApplication))

      def verifyCalledWith(applicationId: ApplicationId, rateLimit: RateLimitTier) =
        ApplicationRepoMock.verify.updateApplicationRateLimit(eqTo(applicationId), eqTo(rateLimit))
    }

    object AddClientSecret {

      def thenReturn(applicationId: ApplicationId)(updatedApplication: ApplicationData) = {
        when(aMock.addClientSecret(eqTo(applicationId), *)).thenReturn(successful(updatedApplication))
      }
    }

    object UpdateClientSecretHash {

      def thenReturn(applicationId: ApplicationId, clientSecretId: String)(updatedApplication: ApplicationData) = {
        when(aMock.updateClientSecretHash(eqTo(applicationId), eqTo(clientSecretId), *)).thenReturn(successful(updatedApplication))
      }

      def verifyCalledWith(applicationId: ApplicationId, clientSecretId: String) =
        ApplicationRepoMock.verify.updateClientSecretHash(eqTo(applicationId), eqTo(clientSecretId), *)
    }

    object UpdateRedirectUris {

      def thenReturn(applicationId: ApplicationId, redirectUris: List[String])(updatedApplication: ApplicationData) = {
        when(aMock.updateRedirectUris(eqTo(applicationId), eqTo(redirectUris))).thenReturn(successful(updatedApplication))
      }

      def verifyCalledWith(applicationId: ApplicationId, redirectUris: List[String]) =
        ApplicationRepoMock.verify.updateRedirectUris(eqTo(applicationId), eqTo(redirectUris))

    }

    object DeleteClientSecret {

      def succeeds(application: ApplicationData, clientSecretId: String) = {
        val otherClientSecrets = application.tokens.production.clientSecrets.filterNot(_.id == clientSecretId)
        val updatedApplication =
          application
            .copy(tokens =
              ApplicationTokens(Token(application.tokens.production.clientId, application.tokens.production.accessToken, otherClientSecrets))
            )

        when(aMock.deleteClientSecret(eqTo(application.id), eqTo(clientSecretId))).thenReturn(successful(updatedApplication))
      }

      def clientSecretNotFound(applicationId: ApplicationId, clientSecretId: String) =
        when(aMock.deleteClientSecret(eqTo(applicationId), eqTo(clientSecretId)))
          .thenThrow(new NotFoundException(s"Client Secret Id [$clientSecretId] not found in Application [${applicationId.value}]"))

      def verifyNeverCalled() = ApplicationRepoMock.verify(never).deleteClientSecret(*[ApplicationId], *)
    }

    object AddCollaborator {

      def succeeds(applicationData: ApplicationData) =
        when(aMock.addCollaborator(*[ApplicationId], *[Collaborator])).thenReturn(successful(applicationData))
    }

    object RemoveCollaborator {

      def succeeds(applicationData: ApplicationData) =
        when(aMock.removeCollaborator(*[ApplicationId], *[UserId])).thenReturn(successful(applicationData))
    }

    object AddApplicationTermsOfUseAcceptance {

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.addApplicationTermsOfUseAcceptance(*[ApplicationId], *[TermsOfUseAcceptance])).thenReturn(successful(applicationData))
    }

    object UpdateApplicationChangeResponsibleIndividualToSelf {

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.updateApplicationChangeResponsibleIndividualToSelf(*[ApplicationId], *[String], *[LaxEmailAddress], *[LocalDateTime], *[SubmissionId], *[Int])).thenReturn(
          successful(
            applicationData
          )
        )
    }

    object UpdateApplicationSetResponsibleIndividual {

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.updateApplicationSetResponsibleIndividual(*[ApplicationId], *[String], *[LaxEmailAddress], *[LocalDateTime], *[SubmissionId], *[Int])).thenReturn(successful(
          applicationData
        ))
    }

    object UpdateApplicationChangeResponsibleIndividual {

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.updateApplicationChangeResponsibleIndividual(*[ApplicationId], *[String], *[LaxEmailAddress], *[LocalDateTime], *[SubmissionId], *[Int])).thenReturn(successful(
          applicationData
        ))
    }

    object FetchProdAppStateHistories {

      def thenReturn(appStateHistories: ApplicationWithStateHistory*) =
        when(aMock.fetchProdAppStateHistories()).thenReturn(Future.successful(appStateHistories.toList))
    }

    object FetchByStatusDetailsAndEnvironment {

      def thenReturn(apps: ApplicationData*) =
        when(aMock.fetchByStatusDetailsAndEnvironment(*, *, *)).thenReturn(successful(apps.toList))
    }

    object FetchByStatusDetailsAndEnvironmentNotAleadyNotified {

      def thenReturn(apps: ApplicationData*) =
        when(aMock.fetchByStatusDetailsAndEnvironmentNotAleadyNotified(*, *, *)).thenReturn(successful(apps.toList))
    }

    object UpdateApplicationName {

      def succeeds() =
        when(aMock.updateApplicationName(*[ApplicationId], *)).thenReturn(successful(mock[ApplicationData]))

      def thenReturn(app: ApplicationData) =
        when(aMock.updateApplicationName(*[ApplicationId], *)).thenReturn(successful(app))
    }

    object UpdateApplicationState {

      def succeeds() =
        when(aMock.updateApplicationState(*[ApplicationId], *[State], *, *, *)).thenReturn(successful(mock[ApplicationData]))

      def thenReturn(app: ApplicationData) =
        when(aMock.updateApplicationState(*[ApplicationId], *[State], *, *, *)).thenReturn(successful(app))
    }

    object UpdatePrivacyPolicyLocation {

      def succeeds() =
        when(aMock.updateApplicationPrivacyPolicyLocation(*[ApplicationId], *[PrivacyPolicyLocation])).thenReturn(successful(mock[ApplicationData]))
    }

    object UpdateLegacyPrivacyPolicyLocation {

      def succeeds() =
        when(aMock.updateLegacyApplicationPrivacyPolicyLocation(*[ApplicationId], *)).thenReturn(successful(mock[ApplicationData]))
    }

    object UpdateTermsAndConditionsPolicyLocation {

      def succeeds() =
        when(aMock.updateApplicationTermsAndConditionsLocation(*[ApplicationId], *[TermsAndConditionsLocation])).thenReturn(successful(mock[ApplicationData]))
    }

    object UpdateLegacyTermsAndConditionsPolicyLocation {

      def succeeds() =
        when(aMock.updateLegacyApplicationTermsAndConditionsLocation(*[ApplicationId], *)).thenReturn(successful(mock[ApplicationData]))
    }
  }

  object ApplicationRepoMock extends BaseApplicationRepoMock {

    val aMock = mock[ApplicationRepository]
  }

  object LenientApplicationRepoMock extends BaseApplicationRepoMock {
    val aMock = mock[ApplicationRepository](withSettings.lenient())
  }
}
