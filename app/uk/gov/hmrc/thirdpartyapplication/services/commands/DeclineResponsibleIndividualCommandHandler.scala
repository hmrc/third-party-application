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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import cats.Apply
import cats.data.{NonEmptyChain, NonEmptyList, Validated, ValidatedNec}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationId
}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{DeclineResponsibleIndividual, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.State

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class DeclineResponsibleIndividualCommandHandler @Inject() (
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def isApplicationIdTheSame(app: ApplicationData, riVerification: ResponsibleIndividualVerification) =
    cond(app.id == riVerification.applicationId, "The given application id is different")

  private def validateToU(app: ApplicationData, cmd: DeclineResponsibleIndividual, riVerification: ResponsibleIndividualToUVerification): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map6(
      isStandardNewJourneyApp(app),
      isPendingResponsibleIndividualVerification(app),
      isApplicationIdTheSame(app, riVerification),
      isResponsibleIndividualDefined(app),
      isRequesterEmailDefined(app),
      isRequesterNameDefined(app)
    ) { case _ => app }
  }

  private def validateUpdate(app: ApplicationData, cmd: DeclineResponsibleIndividual, riVerification: ResponsibleIndividualUpdateVerification): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map4(
      isStandardNewJourneyApp(app),
      isApproved(app),
      isApplicationIdTheSame(app, riVerification),
      isResponsibleIndividualDefined(app)
    ) { case _ => app }
  }

  import UpdateApplicationEvent._

  private def asEventsToU(app: ApplicationData, cmd: DeclineResponsibleIndividual, riVerification: ResponsibleIndividualToUVerification): NonEmptyList[UpdateApplicationEvent] = {
    val responsibleIndividual = getResponsibleIndividual(app).get
    val requesterEmail        = getRequesterEmail(app).get
    val requesterName         = getRequesterName(app).get
    NonEmptyList.of(
      ResponsibleIndividualDeclined(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        responsibleIndividualName = responsibleIndividual.fullName.value,
        responsibleIndividualEmail = responsibleIndividual.emailAddress.value,
        submissionId = riVerification.submissionId,
        submissionIndex = riVerification.submissionInstance,
        code = cmd.code,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      ),
      ApplicationApprovalRequestDeclined(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        decliningUserName = responsibleIndividual.fullName.value,
        decliningUserEmail = responsibleIndividual.emailAddress.value,
        submissionId = riVerification.submissionId,
        submissionIndex = riVerification.submissionInstance,
        reasons = "Responsible individual declined the terms of use.",
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        app.state.name,
        State.TESTING,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  private def asEventsUpdate(app: ApplicationData, cmd: DeclineResponsibleIndividual, riVerification: ResponsibleIndividualUpdateVerification): NonEmptyList[UpdateApplicationEvent] = {
    val responsibleIndividual = riVerification.responsibleIndividual
    NonEmptyList.of(
      ResponsibleIndividualDeclinedUpdate(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(riVerification.requestingAdminEmail),
        responsibleIndividualName = responsibleIndividual.fullName.value,
        responsibleIndividualEmail = responsibleIndividual.emailAddress.value,
        submissionId = riVerification.submissionId,
        submissionIndex = riVerification.submissionInstance,
        code = cmd.code,
        requestingAdminName = riVerification.requestingAdminName,
        requestingAdminEmail = riVerification.requestingAdminEmail
      )
    )
  }

  def process(app: ApplicationData, cmd: DeclineResponsibleIndividual): CommandHandler.Result = {
    responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(cmd.code)).map(maybeRIVerification => {
      maybeRIVerification match {
        case Some(riVerificationToU: ResponsibleIndividualToUVerification)       => validateToU(app, cmd, riVerificationToU) map { _ =>
            asEventsToU(app, cmd, riVerificationToU)
          }
        case Some(riVerificationUpdate: ResponsibleIndividualUpdateVerification) => validateUpdate(app, cmd, riVerificationUpdate) map { _ =>
            asEventsUpdate(app, cmd, riVerificationUpdate)
          }
        case _                                                                   => Validated.Invalid(NonEmptyChain.one(s"No responsibleIndividualVerification found for code ${cmd.code}"))
      }
    })
  }
}
