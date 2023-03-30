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

import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.TermsOfUseInvitationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class TermsOfUseInvitationServiceSpec extends AsyncHmrcSpec {

  trait Setup extends TermsOfUseInvitationRepositoryMockModule with EmailConnectorMockModule with ApplicationTestData {
    implicit val hc = HeaderCarrier()

    val applicationId = ApplicationId.random

    val underTest = new TermsOfUseInvitationService(
      TermsOfUseRepositoryMock.aMock,
      EmailConnectorMock.aMock
    )
  }

  "create invitation" should {
    "return success when the terms of use invitiation can be created" in new Setup {
      EmailConnectorMock.SendNewTermsOfUseInvitation.thenReturnSuccess()
      TermsOfUseRepositoryMock.Create.thenReturnSuccess(TermsOfUseInvitation(applicationId))

      val result = await(underTest.createInvitation(anApplicationData(applicationId)))

      result shouldBe 'defined
    }

    "return failure when the terms of use invitiation cannot be created" in new Setup {
      TermsOfUseRepositoryMock.Create.thenReturnFailure()

      val result = await(underTest.createInvitation(anApplicationData(applicationId)))

      result shouldBe None
    }
  }

  "fetch invitation" should {
    "return an invitation when one is found in the repository" in new Setup {
      val invite = TermsOfUseInvitation(applicationId)

      TermsOfUseRepositoryMock.FetchInvitation.thenReturn(invite)

      val result = await(underTest.fetchInvitation(applicationId))

      result.value should equal(TermsOfUseInvitationResponse(invite.applicationId, invite.createdOn, invite.lastUpdated, invite.dueBy, invite.reminderSent, invite.status))
    }

    "return nothing when no invitation is found in the repository" in new Setup {
      TermsOfUseRepositoryMock.FetchInvitation.thenReturnNone()

      val result = await(underTest.fetchInvitation(applicationId))

      result shouldBe None
    }
  }

  "fetch invitations" should {
    "return an list of all invitations when invitations exist in the repository" in new Setup {
      val invitations = List(
        TermsOfUseInvitation(applicationId)
      )

      TermsOfUseRepositoryMock.FetchAll.thenReturn(invitations)

      val result = await(underTest.fetchInvitations())

      result.size shouldBe 1
    }

    "return empty list when no invitations are found in the repository" in new Setup {
      TermsOfUseRepositoryMock.FetchAll.thenReturn(List.empty)

      val result = await(underTest.fetchInvitations())

      result.size shouldBe 0
    }
  }
}
