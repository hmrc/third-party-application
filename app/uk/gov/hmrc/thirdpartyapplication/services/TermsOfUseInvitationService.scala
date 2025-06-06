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

import java.time.Clock
import java.time.temporal.ChronoUnit._
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.{HasSucceeded, TermsOfUseInvitationResponse, TermsOfUseInvitationWithApplicationResponse, TermsOfUseSearch}
import uk.gov.hmrc.thirdpartyapplication.repository.TermsOfUseInvitationRepository

@Singleton
class TermsOfUseInvitationService @Inject() (
    termsOfUseRepository: TermsOfUseInvitationRepository,
    emailConnector: EmailConnector,
    val clock: Clock,
    config: TermsOfUseInvitationConfig
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with ClockNow {

  val daysUntilDueWhenCreated = config.daysUntilDueWhenCreated
  val daysUntilDueWhenReset   = config.daysUntilDueWhenReset

  def fetchInvitation(applicationId: ApplicationId): Future[Option[TermsOfUseInvitationResponse]] = {
    for {
      inviteF  <- termsOfUseRepository.fetch(applicationId)
      responseF = inviteF.map(invite => TermsOfUseInvitationResponse(invite.applicationId, invite.createdOn, invite.lastUpdated, invite.dueBy, invite.reminderSent, invite.status))
    } yield responseF
  }

  def fetchInvitations(): Future[List[TermsOfUseInvitationResponse]] = {
    for {
      invitesF  <- termsOfUseRepository.fetchAll()
      responsesF = invitesF.map(invite => TermsOfUseInvitationResponse(invite.applicationId, invite.createdOn, invite.lastUpdated, invite.dueBy, invite.reminderSent, invite.status))
    } yield responsesF
  }

  def updateStatus(applicationId: ApplicationId, newState: TermsOfUseInvitationState): Future[HasSucceeded] = {
    termsOfUseRepository.updateState(applicationId, newState)
  }

  def updateResetBackToEmailSent(applicationId: ApplicationId): Future[HasSucceeded] = {
    val newDueByDate = instant().plus(daysUntilDueWhenReset.toMinutes, MINUTES)
    termsOfUseRepository.updateResetBackToEmailSent(applicationId, newDueByDate)
  }

  def search(searchCriteria: TermsOfUseSearch): Future[Seq[TermsOfUseInvitationWithApplicationResponse]] = {
    for {
      invitesF  <- termsOfUseRepository.search(searchCriteria)
      responsesF = invitesF.map(invite =>
                     TermsOfUseInvitationWithApplicationResponse(
                       invite.applicationId,
                       invite.createdOn,
                       invite.lastUpdated,
                       invite.dueBy,
                       invite.reminderSent,
                       invite.status,
                       invite.getApplicationName()
                     )
                   )
    } yield responsesF
  }
}

case class TermsOfUseInvitationConfig(daysUntilDueWhenCreated: FiniteDuration, daysUntilDueWhenReset: FiniteDuration)
