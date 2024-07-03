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

package uk.gov.hmrc.thirdpartyapplication.services.commands.submission

import java.time.temporal.ChronoUnit._
import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SendTermsOfUseInvitation
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models.db.{StoredApplication, TermsOfUseInvitation}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.TermsOfUseInvitationConfig
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class SendTermsOfUseInvitationCommandHandler @Inject() (
    val applicationRepository: ApplicationRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    submissionService: SubmissionsService,
    config: TermsOfUseInvitationConfig,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends SubmissionApprovalCommandsHandler with ApplicationLogger with ClockNow {

  import CommandHandler._
  import CommandFailures._

  private def validate(app: StoredApplication): Future[Validated[Failures, StoredApplication]] = {

    def getExistingRecords(applicationId: ApplicationId): Future[(Option[Submission], Option[TermsOfUseInvitation])] = {
      (
        for {
          maybeSubmission <- submissionService.fetchLatest(applicationId)
          maybeInvitation <- termsOfUseInvitationRepository.fetch(applicationId)
        } yield (maybeSubmission, maybeInvitation)
      )
    }

    getExistingRecords(app.id).map {
      case (maybeSubmission, maybeInvitation) => {
        Apply[Validated[Failures, *]].map4(
          ensureStandardAccess(app),
          isInProduction(app),
          cond(maybeSubmission.isEmpty, s"Submission already exists for application ${app.id.value}"),
          cond(maybeInvitation.isEmpty, s"Terms of Use Invitation already exists for application ${app.id.value}")
        ) { case _ => app }
      }
    }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: SendTermsOfUseInvitation,
      invitation: TermsOfUseInvitation
    ): NonEmptyList[ApplicationEvent] = {

    NonEmptyList.of(TermsOfUseInvitationSent(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = Actors.GatekeeperUser(cmd.gatekeeperUser),
      invitation.dueBy
    ))

  }

  def process(app: StoredApplication, cmd: SendTermsOfUseInvitation): AppCmdResultT = {
    def createInvitation(applicationId: ApplicationId): TermsOfUseInvitation = {
      val daysUntilDueWhenCreated = config.daysUntilDueWhenCreated
      val now                     = Instant.now(clock).truncatedTo(MILLIS)
      TermsOfUseInvitation(
        applicationId,
        now,
        now,
        now.plus(daysUntilDueWhenCreated.toMinutes, MINUTES),
        None,
        EMAIL_SENT
      )
    }
    lazy val noInvitation                                                    = NonEmptyList.one(GenericFailure(s"Failed to create terms of use invitation for application ${app.id}"))

    for {
      validateResult <- E.fromValidatedF(validate(app))
      newInvite      <- E.fromOptionF(termsOfUseInvitationRepository.create(createInvitation(app.id)), noInvitation)
      events          = asEvents(app, cmd, newInvite)
    } yield (app, events)
  }
}
