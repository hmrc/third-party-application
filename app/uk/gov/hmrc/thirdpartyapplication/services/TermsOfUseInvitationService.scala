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

import java.time.temporal.ChronoUnit._
import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

import cats.data.OptionT
import cats.implicits._

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.{StoredApplication, TermsOfUseInvitation}
import uk.gov.hmrc.thirdpartyapplication.models.{HasSucceeded, TermsOfUseInvitationResponse, TermsOfUseInvitationWithApplicationResponse, TermsOfUseSearch}
import uk.gov.hmrc.thirdpartyapplication.repository.TermsOfUseInvitationRepository

@Singleton
class TermsOfUseInvitationService @Inject() (
    termsOfUseRepository: TermsOfUseInvitationRepository,
    emailConnector: EmailConnector,
    clock: Clock,
    config: TermsOfUseInvitationConfig
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  val daysUntilDueWhenCreated = config.daysUntilDueWhenCreated
  val daysUntilDueWhenReset   = config.daysUntilDueWhenReset

  def createInvitation(application: StoredApplication)(implicit hc: HeaderCarrier): Future[Option[TermsOfUseInvitation]] = {
    logger.info(s"Inviting application(${application.id.value}) to complete the new terms of use")
    val now    = Instant.now(clock).truncatedTo(MILLIS)
    val invite = TermsOfUseInvitation(
      application.id,
      now,
      now,
      now.plus(daysUntilDueWhenCreated.toMinutes, MINUTES),
      None,
      EMAIL_SENT
    )
    (
      for {
        newInvite <- OptionT(termsOfUseRepository.create(invite))
        _         <- OptionT.liftF(emailConnector.sendNewTermsOfUseInvitation(newInvite.dueBy, application.name, application.admins.map(_.emailAddress)))
      } yield newInvite
    ).value
  }

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
    val newDueByDate = Instant.now(clock).truncatedTo(MILLIS).plus(daysUntilDueWhenReset.toMinutes, MINUTES)
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
