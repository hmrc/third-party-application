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

package uk.gov.hmrc.thirdpartyapplication.testutils.services

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.{
  ApplicationRepositoryMockModule,
  NotificationRepositoryMockModule,
  ResponsibleIndividualVerificationRepositoryMockModule,
  StateHistoryRepositoryMockModule,
  SubscriptionRepositoryMockModule
}
import uk.gov.hmrc.thirdpartyapplication.mocks.{
  ApiGatewayStoreMockModule,
  ApiPlatformEventServiceMockModule,
  AuditServiceMockModule,
  NotificationServiceMockModule,
  ThirdPartyDelegatedAuthorityServiceMockModule
}
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}
import uk.gov.hmrc.thirdpartyapplication.services.commands.production._
import uk.gov.hmrc.thirdpartyapplication.services.commands.sandbox._
import uk.gov.hmrc.thirdpartyapplication.services.commands.gatekeeper._
import uk.gov.hmrc.thirdpartyapplication.services.commands.deleteapplication._
import uk.gov.hmrc.thirdpartyapplication.services.commands.redirects._
import uk.gov.hmrc.thirdpartyapplication.services.commands.responsibleindividual._
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandDispatcher
import uk.gov.hmrc.thirdpartyapplication.services.commands.gatekeeper.DeclineApplicationApprovalRequestCommandHandler

abstract class ApplicationCommandDispatcherUtils extends AsyncHmrcSpec
    with ApplicationStateUtil
    with ApplicationTestData {

  trait CommonSetup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with SubscriptionRepositoryMockModule
      with NotificationRepositoryMockModule
      with NotificationServiceMockModule
      with ApiPlatformEventServiceMockModule
      with SubmissionsServiceMockModule
      with ApiGatewayStoreMockModule
      with ThirdPartyDelegatedAuthorityServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val response = mock[HttpResponse]

    val mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler =
      mock[ChangeProductionApplicationPrivacyPolicyLocationCommandHandler]

    val mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler =
      mock[ChangeProductionApplicationTermsAndConditionsLocationCommandHandler]
    val mockChangeResponsibleIndividualToSelfCommandHandler: ChangeResponsibleIndividualToSelfCommandHandler                                         = mock[ChangeResponsibleIndividualToSelfCommandHandler]
    val mockChangeResponsibleIndividualToOtherCommandHandler: ChangeResponsibleIndividualToOtherCommandHandler                                       = mock[ChangeResponsibleIndividualToOtherCommandHandler]
    val mockVerifyResponsibleIndividualCommandHandler: VerifyResponsibleIndividualCommandHandler                                                     = mock[VerifyResponsibleIndividualCommandHandler]
    val mockDeclineResponsibleIndividualCommandHandler: DeclineResponsibleIndividualCommandHandler                                                   = mock[DeclineResponsibleIndividualCommandHandler]

    val mockDeclineResponsibleIndividualDidNotVerifyCommandHandler: DeclineResponsibleIndividualDidNotVerifyCommandHandler =
      mock[DeclineResponsibleIndividualDidNotVerifyCommandHandler]
    val mockDeclineApplicationApprovalRequestCommandHandler: DeclineApplicationApprovalRequestCommandHandler               = mock[DeclineApplicationApprovalRequestCommandHandler]
    val mockDeleteApplicationByCollaboratorCommandHandler: DeleteApplicationByCollaboratorCommandHandler                   = mock[DeleteApplicationByCollaboratorCommandHandler]
    val mockDeleteApplicationByGatekeeperCommandHandler: DeleteApplicationByGatekeeperCommandHandler                       = mock[DeleteApplicationByGatekeeperCommandHandler]
    val mockDeleteUnusedApplicationCommandHandler: DeleteUnusedApplicationCommandHandler                                   = mock[DeleteUnusedApplicationCommandHandler]
    val mockDeleteProductionCredentialsApplicationCommandHandler: DeleteProductionCredentialsApplicationCommandHandler     = mock[DeleteProductionCredentialsApplicationCommandHandler]
    val mockAddCollaboratorCommandHandler: AddCollaboratorCommandHandler                                                   = mock[AddCollaboratorCommandHandler]
    val mockRemoveCollaboratorCommandHandler: RemoveCollaboratorCommandHandler                                             = mock[RemoveCollaboratorCommandHandler]
    val mockSubscribeToApiCommandHandler: SubscribeToApiCommandHandler                                                     = mock[SubscribeToApiCommandHandler]
    val mockUnsubscribeFromApiCommandHandler: UnsubscribeFromApiCommandHandler                                             = mock[UnsubscribeFromApiCommandHandler]
    val mockUpdateRedirectUrisCommandHandler: UpdateRedirectUrisCommandHandler                                             = mock[UpdateRedirectUrisCommandHandler]
    val mockChangeGrantLengthCommandHandler: ChangeGrantLengthCommandHandler                                               = mock[ChangeGrantLengthCommandHandler]
    val mockChangeRateLimitTierCommandHandler: ChangeRateLimitTierCommandHandler                                           = mock[ChangeRateLimitTierCommandHandler]
    val mockChangeProductionApplicationNameCommandHandler: ChangeProductionApplicationNameCommandHandler                   = mock[ChangeProductionApplicationNameCommandHandler]
    val mockAddClientSecretCommandHandler: AddClientSecretCommandHandler                                                   = mock[AddClientSecretCommandHandler]
    val mockRemoveClientSecretCommandHandler: RemoveClientSecretCommandHandler                                             = mock[RemoveClientSecretCommandHandler]
    val mockAddRedirectUriCommandHandler: AddRedirectUriCommandHandler                                                     = mock[AddRedirectUriCommandHandler]
    val mockChangeRedirectUriCommandHandler: ChangeRedirectUriCommandHandler                                               = mock[ChangeRedirectUriCommandHandler]
    val mockDeleteRedirectUriCommandHandler: DeleteRedirectUriCommandHandler                                               = mock[DeleteRedirectUriCommandHandler]
    val mockAllowApplicationAutoDeleteCommandHandler: AllowApplicationAutoDeleteCommandHandler                             = mock[AllowApplicationAutoDeleteCommandHandler]
    val mockBlockApplicationAutoDeleteCommandHandler: BlockApplicationAutoDeleteCommandHandler                             = mock[BlockApplicationAutoDeleteCommandHandler]
    val mockChangeIpAllowlistCommandHandler: ChangeIpAllowlistCommandHandler                                               = mock[ChangeIpAllowlistCommandHandler]
    val mockChangeSandboxApplicationNameCommandHandler: ChangeSandboxApplicationNameCommandHandler                         = mock[ChangeSandboxApplicationNameCommandHandler]
    val mockChangeSandboxApplicationDescriptionCommandHandler: ChangeSandboxApplicationDescriptionCommandHandler           = mock[ChangeSandboxApplicationDescriptionCommandHandler]
    val mockChangeSandboxApplicationPrivacyPolicyUrlCommandHandler: ChangeSandboxApplicationPrivacyPolicyUrlCommandHandler = mock[ChangeSandboxApplicationPrivacyPolicyUrlCommandHandler]
    val mockClearSandboxApplicationDescriptionCommandHandler: ClearSandboxApplicationDescriptionCommandHandler             = mock[ClearSandboxApplicationDescriptionCommandHandler]
    val mockRemoveSandboxApplicationPrivacyPolicyUrlCommandHandler: RemoveSandboxApplicationPrivacyPolicyUrlCommandHandler = mock[RemoveSandboxApplicationPrivacyPolicyUrlCommandHandler]

    val deleteApplicationProcessor = new DeleteApplicationProcessor(
      mockDeleteApplicationByCollaboratorCommandHandler,
      mockDeleteUnusedApplicationCommandHandler,
      mockDeleteProductionCredentialsApplicationCommandHandler
    )
    
    val redirectUrisProcessor = new RedirectUrisProcessor(
      mockAddRedirectUriCommandHandler,
      mockDeleteRedirectUriCommandHandler,
      mockChangeRedirectUriCommandHandler,
      mockUpdateRedirectUrisCommandHandler
    )

    val riProcessor = new RIProcessor(
      mockChangeResponsibleIndividualToSelfCommandHandler,
      mockChangeResponsibleIndividualToOtherCommandHandler,
      mockVerifyResponsibleIndividualCommandHandler,
      mockDeclineResponsibleIndividualCommandHandler,
      mockDeclineResponsibleIndividualDidNotVerifyCommandHandler
    )

    val sandboxProcessor = new SandboxProcessor(
      mockChangeSandboxApplicationNameCommandHandler,
      mockChangeSandboxApplicationDescriptionCommandHandler,
      mockChangeSandboxApplicationPrivacyPolicyUrlCommandHandler,
      mockClearSandboxApplicationDescriptionCommandHandler,
      mockRemoveSandboxApplicationPrivacyPolicyUrlCommandHandler
    )

    val gatekeeperProcessor = new GatekeeperProcessor(
      mockDeleteApplicationByGatekeeperCommandHandler,
      mockAllowApplicationAutoDeleteCommandHandler,
      mockBlockApplicationAutoDeleteCommandHandler,
      mockChangeGrantLengthCommandHandler,
      mockChangeRateLimitTierCommandHandler,
      mockChangeProductionApplicationNameCommandHandler,
      mockDeclineApplicationApprovalRequestCommandHandler
    )

    val productionProcessor = new ProductionProcessor(
      mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
      mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler
    )

    val underTest = new ApplicationCommandDispatcher(
      ApplicationRepoMock.aMock,
      NotificationServiceMock.aMock,
      ApiPlatformEventServiceMock.aMock,
      AuditServiceMock.aMock,
      deleteApplicationProcessor,
      redirectUrisProcessor,
      riProcessor,
      sandboxProcessor,
      gatekeeperProcessor,
      productionProcessor,

      mockAddClientSecretCommandHandler,
      mockRemoveClientSecretCommandHandler,

      mockAddCollaboratorCommandHandler,
      mockRemoveCollaboratorCommandHandler,

      mockSubscribeToApiCommandHandler,
      mockUnsubscribeFromApiCommandHandler,

      mockChangeIpAllowlistCommandHandler
    )
  }
}
