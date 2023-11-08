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

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{LaxEmailAddress, UserId, _}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ClientSecret, Collaborator, IpAllowlist, RateLimitTier, State}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{PrivacyPolicyLocation, SubmissionId, TermsAndConditionsLocation, TermsOfUseAcceptance}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Token
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationTokens

trait ApplicationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseApplicationRepoMock {
    def aMock: ApplicationRepository

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object Fetch {

      def thenReturn(applicationData: StoredApplication) =
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

      def thenReturnWhen(clientId: ClientId)(applicationData: StoredApplication) =
        when(aMock.fetchByClientId(eqTo(clientId))).thenReturn(successful(Some(applicationData)))

      def thenReturnNone() =
        when(aMock.fetchByClientId(*[ClientId])).thenReturn(successful(None))

      def thenReturnNoneWhen(clientId: ClientId) =
        when(aMock.fetchByClientId(eqTo(clientId))).thenReturn(successful(None))

      def thenFail(failWith: Throwable) =
        when(aMock.fetchByClientId(*[ClientId])).thenReturn(failed(failWith))
    }

    object Save {
      private val defaultFn = (a: StoredApplication) => successful(a)

      def thenAnswer(fn: StoredApplication => Future[StoredApplication] = defaultFn) =
        when(aMock.save(*)).thenAnswer(fn)

      def thenAnswer() =
        when(aMock.save(*)).thenAnswer((app: StoredApplication) => successful(app))

      def thenReturn(applicationData: StoredApplication) =
        when(aMock.save(*)).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.save(*)).thenReturn(failed(failWith))

      def verifyCalledWith(applicationData: StoredApplication) =
        verify.save(eqTo(applicationData))

      def verifyNeverCalled() =
        verify(never).save(*)

      def verifyCalled(): StoredApplication = {
        val applicationDataArgumentCaptor = ArgCaptor[StoredApplication]
        verify.save(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor.value
      }

      def verifyCalled(mode: VerificationMode): Captor[StoredApplication] = {
        val applicationDataArgumentCaptor = ArgCaptor[StoredApplication]
        verify(mode).save(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor
      }

      def verifyNothingSaved(): Unit = {
        verifyCalled(never)
      }
    }

    object FetchStandardNonTestingApps {

      def thenReturn(apps: StoredApplication*) =
        when(aMock.fetchStandardNonTestingApps()).thenReturn(successful(apps.toList))
    }

    object FetchByName {

      def thenReturnEmptyList() =
        when(aMock.fetchApplicationsByName(*)).thenReturn(successful(List.empty))

      def thenReturn(apps: StoredApplication*) =
        when(aMock.fetchApplicationsByName(*)).thenReturn(successful(apps.toList))

      def thenReturnWhen(name: String)(apps: StoredApplication*) =
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

      def thenReturn(applicationData: StoredApplication) =
        when(aMock.delete(*[ApplicationId], *)).thenReturn(successful(applicationData))
    }

    object HardDelete {

      def verifyCalledWith(id: ApplicationId) =
        ApplicationRepoMock.verify.hardDelete(eqTo(id))

      def thenReturnHasSucceeded() =
        when(aMock.hardDelete(*[ApplicationId])).thenReturn(successful(HasSucceeded))
    }

    object FetchByServerToken {

      def thenReturnWhen(serverToken: String)(applicationData: StoredApplication) =
        when(aMock.fetchByServerToken(eqTo(serverToken))).thenReturn(successful(Some(applicationData)))

      def thenReturnNoneWhen(serverToken: String) =
        when(aMock.fetchByServerToken(eqTo(serverToken))).thenReturn(successful(None))

    }

    object FetchVerifiableUpliftBy {

      def thenReturnNoneWhen(verificationCode: String) =
        when(aMock.fetchVerifiableUpliftBy(eqTo(verificationCode))).thenReturn(successful(None))

      def thenReturnWhen(verificationCode: String)(applicationData: StoredApplication) =
        when(aMock.fetchVerifiableUpliftBy(eqTo(verificationCode))).thenReturn(successful(Some(applicationData)))

    }

    object FetchAllForContent {

      def thenReturnEmptyWhen(apiContext: ApiContext) =
        when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiContext: ApiContext)(apps: StoredApplication*) =
        when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(apps.toList))

    }

    object FetchAllForEmail {

      def thenReturnWhen(emailAddress: String)(apps: StoredApplication*) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(apps.toList))

      def thenReturnEmptyWhen(emailAddress: String) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(List.empty))
    }

    object fetchAllForUserId {

      def thenReturnWhen(userId: UserId, includeDeleted: Boolean)(apps: StoredApplication*) =
        when(aMock.fetchAllForUserId(eqTo(userId), eqTo(includeDeleted))).thenReturn(successful(apps.toList))
    }

    object FetchAllForApiIdentifier {

      def thenReturnEmptyWhen(apiIdentifier: ApiIdentifier) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiIdentifier: ApiIdentifier)(apps: StoredApplication*) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(apps.toList))
    }

    object FetchAllWithNoSubscriptions {

      def thenReturn(apps: StoredApplication*) =
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

    object FindAndRecordApplicationUsage {

      def thenReturnWhen(clientId: ClientId)(applicationData: StoredApplication) =
        when(aMock.findAndRecordApplicationUsage(eqTo(clientId))).thenReturn(successful(Some(applicationData)))
    }

    object FindAndRecordServerTokenUsage {

      def thenReturnWhen(serverToken: String)(applicationData: StoredApplication) =
        when(aMock.findAndRecordServerTokenUsage(eqTo(serverToken))).thenReturn(successful(Some(applicationData)))

      def verifyCalledWith(serverToken: String) =
        ApplicationRepoMock.verify.findAndRecordServerTokenUsage(eqTo(serverToken))
    }

    object UpdateIpAllowlist {

      def verifyCalledWith(applicationId: ApplicationId, newIpAllowlist: IpAllowlist) =
        ApplicationRepoMock.verify.updateApplicationIpAllowlist(eqTo(applicationId), eqTo(newIpAllowlist))

      def thenReturnWhen(applicationId: ApplicationId, newIpAllowlist: IpAllowlist)(updatedApplicationData: StoredApplication) =
        when(aMock.updateApplicationIpAllowlist(eqTo(applicationId), eqTo(newIpAllowlist))).thenReturn(successful(updatedApplicationData))
    }

    object UpdateGrantLength {

      def thenReturn() = when(aMock.updateApplicationGrantLength(*[ApplicationId], *)).thenReturn(successful(mock[StoredApplication]))

      def verifyCalledWith(applicationId: ApplicationId, newGrantLength: Int) =
        ApplicationRepoMock.verify.updateApplicationGrantLength(eqTo(applicationId), eqTo(newGrantLength))

      def thenReturnWhen(applicationId: ApplicationId, newGrantLength: Int)(updatedApplicationData: StoredApplication) =
        when(aMock.updateApplicationGrantLength(eqTo(applicationId), eqTo(newGrantLength))).thenReturn(successful(updatedApplicationData))
    }

    object SearchApplications {

      def thenReturn(data: PaginatedApplicationData) =
        when(aMock.searchApplications(*)(*)).thenReturn(successful(data))
    }

    object ProcessAll {

      def thenReturn() = {
        when(aMock.processAll(*)).thenReturn(successful(()))
      }

      def verify() = {
        val captor = ArgCaptor[StoredApplication => Unit]
        ApplicationRepoMock.verify.processAll(captor)
        captor.value
      }
    }

    object RecordClientSecretUsage {

      def verifyNeverCalled() =
        ApplicationRepoMock.verify(never).recordClientSecretUsage(*[ApplicationId], *[ClientSecret.Id])

      def thenReturnWhen(applicationId: ApplicationId, clientSecretId: ClientSecret.Id)(applicationData: StoredApplication) =
        when(aMock.recordClientSecretUsage(eqTo(applicationId), eqTo(clientSecretId))).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.recordClientSecretUsage(*[ApplicationId], *[ClientSecret.Id])).thenReturn(failed(failWith))
    }

    object UpdateApplicationRateLimit {

      def thenReturn(applicationId: ApplicationId, rateLimit: RateLimitTier)(updatedApplication: StoredApplication) =
        when(aMock.updateApplicationRateLimit(eqTo(applicationId), eqTo(rateLimit))).thenReturn(successful(updatedApplication))

      def verifyCalledWith(applicationId: ApplicationId, rateLimit: RateLimitTier) =
        ApplicationRepoMock.verify.updateApplicationRateLimit(eqTo(applicationId), eqTo(rateLimit))
    }

    object AddClientSecret {

      def thenReturn(applicationId: ApplicationId)(updatedApplication: StoredApplication) = {
        when(aMock.addClientSecret(eqTo(applicationId), *)).thenReturn(successful(updatedApplication))
      }
    }

    object UpdateClientSecretHash {

      def thenReturn(applicationId: ApplicationId, clientSecretId: ClientSecret.Id)(updatedApplication: StoredApplication) = {
        when(aMock.updateClientSecretHash(eqTo(applicationId), eqTo(clientSecretId), *)).thenReturn(successful(updatedApplication))
      }

      def verifyCalledWith(applicationId: ApplicationId, clientSecretId: ClientSecret.Id) =
        ApplicationRepoMock.verify.updateClientSecretHash(eqTo(applicationId), eqTo(clientSecretId), *)
    }

    object UpdateRedirectUris {

      def thenReturn(redirectUris: List[String])(updatedApplication: StoredApplication) = {
        when(aMock.updateRedirectUris(eqTo(updatedApplication.id), eqTo(redirectUris))).thenReturn(successful(updatedApplication))
      }

      def verifyCalledWith(applicationId: ApplicationId, redirectUris: List[String]) =
        ApplicationRepoMock.verify.updateRedirectUris(eqTo(applicationId), eqTo(redirectUris))
    }

    object DeleteClientSecret {

      def succeeds(application: StoredApplication, clientSecretId: ClientSecret.Id) = {
        val otherClientSecrets = application.tokens.production.clientSecrets.filterNot(_.id == clientSecretId)
        val updatedApplication =
          application
            .copy(tokens =
              ApplicationTokens(Token(application.tokens.production.clientId, application.tokens.production.accessToken, otherClientSecrets))
            )

        when(aMock.deleteClientSecret(eqTo(application.id), eqTo(clientSecretId))).thenReturn(successful(updatedApplication))
      }

      def clientSecretNotFound(applicationId: ApplicationId, clientSecretId: ClientSecret.Id) =
        when(aMock.deleteClientSecret(eqTo(applicationId), eqTo(clientSecretId)))
          .thenThrow(new NotFoundException(s"Client Secret Id [$clientSecretId] not found in Application [${applicationId.value}]"))

      def verifyNeverCalled() = ApplicationRepoMock.verify(never).deleteClientSecret(*[ApplicationId], *[ClientSecret.Id])
    }

    object UpdateAllowAutoDelete {

      def thenReturnWhen(allowAutoDelete: Boolean)(updatedApplication: StoredApplication) = {
        when(aMock.updateAllowAutoDelete(eqTo(updatedApplication.id), eqTo(allowAutoDelete))).thenReturn(successful(updatedApplication))
      }

      def verifyCalledWith(applicationId: ApplicationId, allowAutoDelete: Boolean) =
        ApplicationRepoMock.verify.updateAllowAutoDelete(eqTo(applicationId), eqTo(allowAutoDelete))
    }

    object AddCollaborator {

      def succeeds(applicationData: StoredApplication) =
        when(aMock.addCollaborator(*[ApplicationId], *[Collaborator])).thenReturn(successful(applicationData))
    }

    object RemoveCollaborator {

      def succeeds(applicationData: StoredApplication) =
        when(aMock.removeCollaborator(*[ApplicationId], *[UserId])).thenReturn(successful(applicationData))
    }

    object AddApplicationTermsOfUseAcceptance {

      def thenReturn(applicationData: StoredApplication) =
        when(aMock.addApplicationTermsOfUseAcceptance(*[ApplicationId], *[TermsOfUseAcceptance])).thenReturn(successful(applicationData))
    }

    object UpdateApplicationChangeResponsibleIndividualToSelf {

      def thenReturn(applicationData: StoredApplication) =
        when(aMock.updateApplicationChangeResponsibleIndividualToSelf(*[ApplicationId], *[String], *[LaxEmailAddress], *[LocalDateTime], *[SubmissionId], *[Int])).thenReturn(
          successful(
            applicationData
          )
        )
    }

    object UpdateApplicationSetResponsibleIndividual {

      def thenReturn(applicationData: StoredApplication) =
        when(aMock.updateApplicationSetResponsibleIndividual(*[ApplicationId], *[String], *[LaxEmailAddress], *[LocalDateTime], *[SubmissionId], *[Int])).thenReturn(successful(
          applicationData
        ))
    }

    object UpdateApplicationChangeResponsibleIndividual {

      def thenReturn(applicationData: StoredApplication) =
        when(aMock.updateApplicationChangeResponsibleIndividual(*[ApplicationId], *[String], *[LaxEmailAddress], *[LocalDateTime], *[SubmissionId], *[Int])).thenReturn(successful(
          applicationData
        ))
    }

    object FetchProdAppStateHistories {

      def thenReturn(appStateHistories: ApplicationWithStateHistory*) =
        when(aMock.fetchProdAppStateHistories()).thenReturn(Future.successful(appStateHistories.toList))
    }

    object FetchByStatusDetailsAndEnvironment {

      def thenReturn(apps: StoredApplication*) =
        when(aMock.fetchByStatusDetailsAndEnvironment(*, *, *)).thenReturn(successful(apps.toList))
    }

    object FetchByStatusDetailsAndEnvironmentNotAleadyNotified {

      def thenReturn(apps: StoredApplication*) =
        when(aMock.fetchByStatusDetailsAndEnvironmentNotAleadyNotified(*, *, *)).thenReturn(successful(apps.toList))
    }

    object UpdateApplicationName {

      def succeeds() =
        when(aMock.updateApplicationName(*[ApplicationId], *)).thenReturn(successful(mock[StoredApplication]))

      def thenReturn(app: StoredApplication) =
        when(aMock.updateApplicationName(*[ApplicationId], *)).thenReturn(successful(app))
    }

    object UpdateApplicationState {

      def succeeds() =
        when(aMock.updateApplicationState(*[ApplicationId], *[State], *, *, *)).thenReturn(successful(mock[StoredApplication]))

      def thenReturn(app: StoredApplication) =
        when(aMock.updateApplicationState(*[ApplicationId], *[State], *, *, *)).thenReturn(successful(app))
    }

    object UpdatePrivacyPolicyLocation {

      def succeeds() =
        when(aMock.updateApplicationPrivacyPolicyLocation(*[ApplicationId], *[PrivacyPolicyLocation])).thenReturn(successful(mock[StoredApplication]))
    }

    object UpdateLegacyPrivacyPolicyLocation {

      def succeeds() =
        when(aMock.updateLegacyApplicationPrivacyPolicyLocation(*[ApplicationId], *)).thenReturn(successful(mock[StoredApplication]))
    }

    object UpdateTermsAndConditionsPolicyLocation {

      def succeeds() =
        when(aMock.updateApplicationTermsAndConditionsLocation(*[ApplicationId], *[TermsAndConditionsLocation])).thenReturn(successful(mock[StoredApplication]))
    }

    object UpdateLegacyTermsAndConditionsPolicyLocation {

      def succeeds() =
        when(aMock.updateLegacyApplicationTermsAndConditionsLocation(*[ApplicationId], *)).thenReturn(successful(mock[StoredApplication]))
    }
  }

  object ApplicationRepoMock extends BaseApplicationRepoMock {

    val aMock = mock[ApplicationRepository]
  }

  object LenientApplicationRepoMock extends BaseApplicationRepoMock {
    val aMock = mock[ApplicationRepository](withSettings.lenient())
  }
}
