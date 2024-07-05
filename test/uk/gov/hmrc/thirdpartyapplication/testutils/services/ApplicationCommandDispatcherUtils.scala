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
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandDispatcher
import uk.gov.hmrc.thirdpartyapplication.services.commands.block.{BlockApplicationCommandHandler, BlockCommandsProcessor, UnblockApplicationCommandHandler}
import uk.gov.hmrc.thirdpartyapplication.services.commands.clientsecret._
import uk.gov.hmrc.thirdpartyapplication.services.commands.collaborator._
import uk.gov.hmrc.thirdpartyapplication.services.commands.delete._
import uk.gov.hmrc.thirdpartyapplication.services.commands.grantlength._
import uk.gov.hmrc.thirdpartyapplication.services.commands.ipallowlist._
import uk.gov.hmrc.thirdpartyapplication.services.commands.namedescription._
import uk.gov.hmrc.thirdpartyapplication.services.commands.policy._
import uk.gov.hmrc.thirdpartyapplication.services.commands.ratelimit._
import uk.gov.hmrc.thirdpartyapplication.services.commands.redirecturi._
import uk.gov.hmrc.thirdpartyapplication.services.commands.submission._
import uk.gov.hmrc.thirdpartyapplication.services.commands.subscription._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

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
    val mockResendRequesterEmailVerificationCommandHandler: ResendRequesterEmailVerificationCommandHandler                                           = mock[ResendRequesterEmailVerificationCommandHandler]

    val mockDeclineResponsibleIndividualDidNotVerifyCommandHandler: DeclineResponsibleIndividualDidNotVerifyCommandHandler =
      mock[DeclineResponsibleIndividualDidNotVerifyCommandHandler]
    val mockSubmitApplicationApprovalRequestCommandHandler: SubmitApplicationApprovalRequestCommandHandler                 = mock[SubmitApplicationApprovalRequestCommandHandler]
    val mockGrantApplicationApprovalRequestCommandHandler: GrantApplicationApprovalRequestCommandHandler                   = mock[GrantApplicationApprovalRequestCommandHandler]
    val mockGrantTermsOfUseApprovalCommandHander: GrantTermsOfUseApprovalCommandHandler                                    = mock[GrantTermsOfUseApprovalCommandHandler]
    val mockSubmitTermsOfUseApprovalCommandHandler: SubmitTermsOfUseApprovalCommandHandler                                 = mock[SubmitTermsOfUseApprovalCommandHandler]
    val mockSendTermsOfUseInvitationCommandHandler: SendTermsOfUseInvitationCommandHandler                                 = mock[SendTermsOfUseInvitationCommandHandler]
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
    val mockBlockApplicationCommandHandler: BlockApplicationCommandHandler                                                 = mock[BlockApplicationCommandHandler]
    val mockUnblockApplicationCommandHandler: UnblockApplicationCommandHandler                                             = mock[UnblockApplicationCommandHandler]

    val mockChangeSandboxApplicationPrivacyPolicyUrlCommandHandler: ChangeSandboxApplicationPrivacyPolicyUrlCommandHandler =
      mock[ChangeSandboxApplicationPrivacyPolicyUrlCommandHandler]
    val mockClearSandboxApplicationDescriptionCommandHandler: ClearSandboxApplicationDescriptionCommandHandler             = mock[ClearSandboxApplicationDescriptionCommandHandler]

    val mockRemoveSandboxApplicationPrivacyPolicyUrlCommandHandler: RemoveSandboxApplicationPrivacyPolicyUrlCommandHandler =
      mock[RemoveSandboxApplicationPrivacyPolicyUrlCommandHandler]

    val mockChangeSandboxApplicationTermsAndConditionsUrlCommandHandler: ChangeSandboxApplicationTermsAndConditionsUrlCommandHandler =
      mock[ChangeSandboxApplicationTermsAndConditionsUrlCommandHandler]

    val mockRemoveSandboxApplicationTermsAndConditionsUrlCommandHandler: RemoveSandboxApplicationTermsAndConditionsUrlCommandHandler =
      mock[RemoveSandboxApplicationTermsAndConditionsUrlCommandHandler]

    val blockCommandsProcessor = new BlockCommandsProcessor(
      mockBlockApplicationCommandHandler,
      mockUnblockApplicationCommandHandler
    )

    val clientSecretCommandsProcessor = new ClientSecretCommandsProcessor(
      mockAddClientSecretCommandHandler,
      mockRemoveClientSecretCommandHandler
    )

    val collaboratorCommandsProcessor = new CollaboratorCommandsProcessor(
      mockAddCollaboratorCommandHandler,
      mockRemoveCollaboratorCommandHandler
    )

    val deleteCommandsProcessor = new DeleteCommandsProcessor(
      mockDeleteApplicationByGatekeeperCommandHandler,
      mockAllowApplicationAutoDeleteCommandHandler,
      mockBlockApplicationAutoDeleteCommandHandler,
      mockDeleteApplicationByCollaboratorCommandHandler,
      mockDeleteUnusedApplicationCommandHandler,
      mockDeleteProductionCredentialsApplicationCommandHandler
    )

    val grantLengthCommandsProcessor = new GrantLengthCommandsProcessor(
      mockChangeGrantLengthCommandHandler
    )

    val ipAllowListCommandsProcessor = new IpAllowListCommandsProcessor(
      mockChangeIpAllowlistCommandHandler
    )

    val nameDescriptionCommandsProcessor = new NameDescriptionCommandsProcessor(
      mockChangeProductionApplicationNameCommandHandler,
      mockChangeSandboxApplicationNameCommandHandler,
      mockChangeSandboxApplicationDescriptionCommandHandler,
      mockClearSandboxApplicationDescriptionCommandHandler
    )

    val policyCommandsProcessor = new PolicyCommandsProcessor(
      mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
      mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
      mockChangeSandboxApplicationPrivacyPolicyUrlCommandHandler,
      mockRemoveSandboxApplicationPrivacyPolicyUrlCommandHandler,
      mockChangeSandboxApplicationTermsAndConditionsUrlCommandHandler,
      mockRemoveSandboxApplicationTermsAndConditionsUrlCommandHandler
    )

    val rateLimitCommandsProcessor = new RateLimitCommandsProcessor(
      mockChangeRateLimitTierCommandHandler
    )

    val redirectUriCommandsProcessor = new RedirectUriCommandsProcessor(
      mockAddRedirectUriCommandHandler,
      mockDeleteRedirectUriCommandHandler,
      mockChangeRedirectUriCommandHandler,
      mockUpdateRedirectUrisCommandHandler
    )

    val submissionsCommandsProcessor = new SubmissionCommandsProcessor(
      mockChangeResponsibleIndividualToSelfCommandHandler,
      mockChangeResponsibleIndividualToOtherCommandHandler,
      mockVerifyResponsibleIndividualCommandHandler,
      mockDeclineApplicationApprovalRequestCommandHandler,
      mockDeclineResponsibleIndividualCommandHandler,
      mockDeclineResponsibleIndividualDidNotVerifyCommandHandler,
      mockResendRequesterEmailVerificationCommandHandler,
      mockSubmitApplicationApprovalRequestCommandHandler,
      mockSubmitTermsOfUseApprovalCommandHandler,
      mockGrantApplicationApprovalRequestCommandHandler,
      mockGrantTermsOfUseApprovalCommandHander,
      mockSendTermsOfUseInvitationCommandHandler
    )

    val subscriptionCommandsProcessor = new SubscriptionCommandsProcessor(
      mockSubscribeToApiCommandHandler,
      mockUnsubscribeFromApiCommandHandler
    )

    val underTest = new ApplicationCommandDispatcher(
      ApplicationRepoMock.aMock,
      NotificationServiceMock.aMock,
      ApiPlatformEventServiceMock.aMock,
      AuditServiceMock.aMock,
      clientSecretCommandsProcessor,
      collaboratorCommandsProcessor,
      deleteCommandsProcessor,
      grantLengthCommandsProcessor,
      ipAllowListCommandsProcessor,
      nameDescriptionCommandsProcessor,
      policyCommandsProcessor,
      rateLimitCommandsProcessor,
      redirectUriCommandsProcessor,
      submissionsCommandsProcessor,
      subscriptionCommandsProcessor,
      blockCommandsProcessor
    )
  }
}
