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
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandService
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

abstract class ApplicationCommandServiceUtils extends AsyncHmrcSpec
    with ApplicationStateUtil
    with ApplicationTestData {

  trait CommonSetup extends AuditServiceMockModule
      with ApplicationRepositoryMockModule
      with ResponsibleIndividualVerificationRepositoryMockModule
      with StateHistoryRepositoryMockModule
      with NotificationRepositoryMockModule
      with NotificationServiceMockModule
      with ApiPlatformEventServiceMockModule
      with SubmissionsServiceMockModule
      with ApiGatewayStoreMockModule
      with ThirdPartyDelegatedAuthorityServiceMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val response = mock[HttpResponse]

    val mockChangeResponsibleIndividualToOtherCommandHandler: ChangeResponsibleIndividualToOtherCommandHandler = mock[ChangeResponsibleIndividualToOtherCommandHandler]
    val mockVerifyResponsibleIndividualCommandHandler: VerifyResponsibleIndividualCommandHandler               = mock[VerifyResponsibleIndividualCommandHandler]

    val mockDeclineResponsibleIndividualDidNotVerifyCommandHandler: DeclineResponsibleIndividualDidNotVerifyCommandHandler =
      mock[DeclineResponsibleIndividualDidNotVerifyCommandHandler]
    val mockDeclineApplicationApprovalRequestCommandHandler: DeclineApplicationApprovalRequestCommandHandler               = mock[DeclineApplicationApprovalRequestCommandHandler]

    val underTest = new ApplicationCommandService(
      ApplicationRepoMock.aMock,
      ResponsibleIndividualVerificationRepositoryMock.aMock,
      StateHistoryRepoMock.aMock,
      NotificationRepositoryMock.aMock,
      NotificationServiceMock.aMock,
      ApiPlatformEventServiceMock.aMock,
      SubmissionsServiceMock.aMock,
      ThirdPartyDelegatedAuthorityServiceMock.aMock,
      ApiGatewayStoreMock.aMock,
      AuditServiceMock.aMock,
      mockChangeResponsibleIndividualToOtherCommandHandler
    )
  }

}
