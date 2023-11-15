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

import java.time.Instant
import java.time.temporal.ChronoUnit._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.TermsOfUseInvitationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.{EMAIL_SENT, REMINDER_EMAIL_SENT}
import uk.gov.hmrc.thirdpartyapplication.models.db.{TermsOfUseInvitation, TermsOfUseInvitationWithApplication}
import uk.gov.hmrc.thirdpartyapplication.models.{HasSucceeded}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseSearch

class TermsOfUseInvitationServiceSpec extends AsyncHmrcSpec {

  trait Setup extends TermsOfUseInvitationRepositoryMockModule with EmailConnectorMockModule with ApplicationTestData {
    implicit val hc = HeaderCarrier()

    val applicationId = ApplicationId.random
    val application   = anApplicationData(applicationId)
    val nowInstant    = Instant.now(clock).truncatedTo(MILLIS)
    val invite        = TermsOfUseInvitation(applicationId, nowInstant, nowInstant, nowInstant.plus(21, DAYS), None, EMAIL_SENT)
    val inviteWithApp = TermsOfUseInvitationWithApplication(applicationId, nowInstant, nowInstant, nowInstant.plus(21, DAYS), None, EMAIL_SENT, Set(application))

    val underTest = new TermsOfUseInvitationService(
      TermsOfUseInvitationRepositoryMock.aMock,
      EmailConnectorMock.aMock,
      clock,
      TermsOfUseInvitationConfig(FiniteDuration(21, java.util.concurrent.TimeUnit.DAYS), FiniteDuration(30, java.util.concurrent.TimeUnit.DAYS))
    )
  }

  "create invitation" should {
    "return success when the terms of use invitiation can be created" in new Setup {
      EmailConnectorMock.SendNewTermsOfUseInvitation.thenReturnSuccess()
      TermsOfUseInvitationRepositoryMock.Create.thenReturnSuccess(invite)

      val result = await(underTest.createInvitation(anApplicationData(applicationId)))

      result.isDefined shouldBe true
    }

    "return failure when the terms of use invitiation cannot be created" in new Setup {
      TermsOfUseInvitationRepositoryMock.Create.thenReturnFailure()

      val result = await(underTest.createInvitation(anApplicationData(applicationId)))

      result shouldBe None
    }
  }

  "fetch invitation" should {
    "return an invitation when one is found in the repository" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturn(invite)

      val result = await(underTest.fetchInvitation(applicationId))

      result.value should equal(TermsOfUseInvitationResponse(invite.applicationId, invite.createdOn, invite.lastUpdated, invite.dueBy, invite.reminderSent, invite.status))
    }

    "return nothing when no invitation is found in the repository" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchInvitation.thenReturnNone()

      val result = await(underTest.fetchInvitation(applicationId))

      result shouldBe None
    }
  }

  "fetch invitations" should {
    "return an list of all invitations when invitations exist in the repository" in new Setup {
      val invitations = List(invite)

      TermsOfUseInvitationRepositoryMock.FetchAll.thenReturn(invitations)

      val result = await(underTest.fetchInvitations())

      result.size shouldBe 1
    }

    "return empty list when no invitations are found in the repository" in new Setup {
      TermsOfUseInvitationRepositoryMock.FetchAll.thenReturn(List.empty)

      val result = await(underTest.fetchInvitations())

      result.size shouldBe 0
    }
  }

  "update status" should {
    "call update status in the repository" in new Setup {
      TermsOfUseInvitationRepositoryMock.UpdateState.thenReturn()

      val result = await(underTest.updateStatus(applicationId, REMINDER_EMAIL_SENT))

      result shouldBe HasSucceeded
      TermsOfUseInvitationRepositoryMock.UpdateState.verifyCalledWith(applicationId, REMINDER_EMAIL_SENT)
    }
  }

  "update status reset back to Email Sent" should {
    "call update status in the repository" in new Setup {
      val newDueBy = nowInstant.plus(30, DAYS)
      TermsOfUseInvitationRepositoryMock.UpdateResetBackToEmailSent.thenReturn()

      val result = await(underTest.updateResetBackToEmailSent(applicationId))

      result shouldBe HasSucceeded
      TermsOfUseInvitationRepositoryMock.UpdateResetBackToEmailSent.verifyCalledWith(applicationId, newDueBy)
    }
  }

  "search invitations" should {
    "return an list of all invitations when invitations exist in the repository" in new Setup {
      val invitations = List(inviteWithApp)

      TermsOfUseInvitationRepositoryMock.Search.thenReturn(invitations)

      val result = await(underTest.search(TermsOfUseSearch()))

      result.size shouldBe 1
    }

    "return empty list when no invitations are found in the repository" in new Setup {
      TermsOfUseInvitationRepositoryMock.Search.thenReturn(List.empty)

      val result = await(underTest.search(TermsOfUseSearch()))

      result.size shouldBe 0
    }
  }

}
