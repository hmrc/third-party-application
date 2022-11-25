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

package uk.gov.hmrc.thirdpartyapplication.testutils.services

import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationUpdateService
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import scala.concurrent.ExecutionContext.Implicits.global


abstract class ApplicationUpdateServiceUtils extends AsyncHmrcSpec
  with ApplicationStateUtil
  with ApplicationTestData {

  trait CommonSetup extends AuditServiceMockModule
    with ApplicationRepositoryMockModule
    with ResponsibleIndividualVerificationRepositoryMockModule
    with StateHistoryRepositoryMockModule
    with SubscriptionRepositoryMockModule
    with NotificationServiceMockModule
    with ApiPlatformEventServiceMockModule
    with SubmissionsServiceMockModule
    with ApiGatewayStoreMockModule
    with ThirdPartyDelegatedAuthorityServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val response = mock[HttpResponse]

    val mockAddClientSecretCommandHandler: AddClientSecretCommandHandler = mock[AddClientSecretCommandHandler]
    val mockRemoveClientSecretCommandHandler: RemoveClientSecretCommandHandler = mock[RemoveClientSecretCommandHandler]
    val mockChangeProductionApplicationNameCommandHandler: ChangeProductionApplicationNameCommandHandler = mock[ChangeProductionApplicationNameCommandHandler]
    val mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler: ChangeProductionApplicationPrivacyPolicyLocationCommandHandler = mock[ChangeProductionApplicationPrivacyPolicyLocationCommandHandler]
    val mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler: ChangeProductionApplicationTermsAndConditionsLocationCommandHandler = mock[ChangeProductionApplicationTermsAndConditionsLocationCommandHandler]
    val mockChangeResponsibleIndividualToSelfCommandHandler: ChangeResponsibleIndividualToSelfCommandHandler = mock[ChangeResponsibleIndividualToSelfCommandHandler]
    val mockChangeResponsibleIndividualToOtherCommandHandler: ChangeResponsibleIndividualToOtherCommandHandler = mock[ChangeResponsibleIndividualToOtherCommandHandler]
    val mockVerifyResponsibleIndividualCommandHandler: VerifyResponsibleIndividualCommandHandler = mock[VerifyResponsibleIndividualCommandHandler]
    val mockDeclineResponsibleIndividualCommandHandler: DeclineResponsibleIndividualCommandHandler = mock[DeclineResponsibleIndividualCommandHandler]
    val mockDeclineResponsibleIndividualDidNotVerifyCommandHandler: DeclineResponsibleIndividualDidNotVerifyCommandHandler = mock[DeclineResponsibleIndividualDidNotVerifyCommandHandler]
    val mockDeclineApplicationApprovalRequestCommandHandler: DeclineApplicationApprovalRequestCommandHandler = mock[DeclineApplicationApprovalRequestCommandHandler]
    val mockDeleteApplicationCommandHandler: DeleteApplicationCommandHandler = mock[DeleteApplicationCommandHandler]
    val mockAddCollaboratorCommandHandler: AddCollaboratorCommandHandler = mock[AddCollaboratorCommandHandler]
    val mockRemoveCollaboratorCommandHandler: RemoveCollaboratorCommandHandler = mock[RemoveCollaboratorCommandHandler]
    val mockSubscribeToApiCommandHandler: SubscribeToApiCommandHandler = mock[SubscribeToApiCommandHandler]
    val mockUnsubscribeFromApiCommandHandler: UnsubscribeFromApiCommandHandler = mock[UnsubscribeFromApiCommandHandler]

    val underTest = new ApplicationUpdateService(
      ApplicationRepoMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      StateHistoryRepoMock.aMock,
      SubscriptionRepoMock.aMock,
      NotificationServiceMock.aMock,
      ApiPlatformEventServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      ThirdPartyDelegatedAuthorityServiceMock.aMock,
      ApiGatewayStoreMock.aMock,
      AuditServiceMock.aMock,
      mockAddClientSecretCommandHandler,
      mockRemoveClientSecretCommandHandler,
      mockChangeProductionApplicationNameCommandHandler,
      mockChangeProductionApplicationPrivacyPolicyLocationCommandHandler,
      mockChangeProductionApplicationTermsAndConditionsLocationCommandHandler,
      mockChangeResponsibleIndividualToSelfCommandHandler,
      mockChangeResponsibleIndividualToOtherCommandHandler,
      mockVerifyResponsibleIndividualCommandHandler,
      mockDeclineResponsibleIndividualCommandHandler,
      mockDeclineResponsibleIndividualDidNotVerifyCommandHandler,
      mockDeclineApplicationApprovalRequestCommandHandler,
      mockDeleteApplicationCommandHandler,
      mockAddCollaboratorCommandHandler,
      mockRemoveCollaboratorCommandHandler,
      mockSubscribeToApiCommandHandler,
      mockUnsubscribeFromApiCommandHandler
    )
  }

}
