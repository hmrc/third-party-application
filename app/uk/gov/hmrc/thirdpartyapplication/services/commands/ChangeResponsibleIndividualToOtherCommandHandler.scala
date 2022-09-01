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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import cats.Apply
import cats.data.{NonEmptyChain, NonEmptyList, Validated, ValidatedNec}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualToUVerification, ResponsibleIndividualUpdateVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ChangeResponsibleIndividualToOther, ImportantSubmissionData, Standard, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.State

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ChangeResponsibleIndividualToOtherCommandHandler @Inject()(
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def isNotCurrentRi(name: String, email: String, app: ApplicationData) =
    cond(app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) =>
        ! responsibleIndividual.fullName.value.equalsIgnoreCase(name) || ! responsibleIndividual.emailAddress.value.equalsIgnoreCase(email)
      case _ => true
    }, s"The specified individual is already the RI for this application")

  private def getResponsibleIndividual(app: ApplicationData) =
    app.access match {
      case Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, responsibleIndividual, _, _, _, _))) => Some(responsibleIndividual)
      case _ => None
    }

  private def isResponsibleIndividualDefined(app: ApplicationData) =
    cond(getResponsibleIndividual(app).isDefined, "The responsible individual has not been set for this application")

  private def isApplicationIdTheSame(app: ApplicationData, riVerification: ResponsibleIndividualVerification) = 
    cond(app.id == riVerification.applicationId, "The given application id is different")

  private def getRequesterEmail(app: ApplicationData) =
    app.state.requestedByEmailAddress

  private def isRequesterEmailDefined(app: ApplicationData) =
    cond(getRequesterEmail(app).isDefined, "The requestedByEmailAddress has not been set for this application")

  private def getRequesterName(app: ApplicationData) =
    app.state.requestedByName

  private def isRequesterNameDefined(app: ApplicationData) =
    cond(getRequesterName(app).isDefined, "The requestedByName has not been set for this application")

  private def validateToU(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerification: ResponsibleIndividualToUVerification): ValidatedNec[String, ApplicationData] = {
    Apply[ValidatedNec[String, *]].map6(
      isStandardNewJourneyApp(app),
      isPendingResponsibleIndividualVerification(app),
      isApplicationIdTheSame(app, riVerification),
      isResponsibleIndividualDefined(app),
      isRequesterEmailDefined(app),
      isRequesterNameDefined(app)
    ) { case _ => app }
  }

  private def validateUpdate(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerification: ResponsibleIndividualUpdateVerification): ValidatedNec[String, ApplicationData] = {
    val responsibleIndividual = riVerification.responsibleIndividual
    Apply[ValidatedNec[String, *]].map4(
      isStandardNewJourneyApp(app),
      isApproved(app),
      isApplicationIdTheSame(app, riVerification),
      isNotCurrentRi(responsibleIndividual.fullName.value, responsibleIndividual.emailAddress.value, app)
    ) { case _ => app }
  }

  import UpdateApplicationEvent._

  private def asEventsToU(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerification: ResponsibleIndividualToUVerification): NonEmptyList[UpdateApplicationEvent] = {
    val responsibleIndividual = getResponsibleIndividual(app).get
    val requesterEmail = getRequesterEmail(app).get
    val requesterName = getRequesterName(app).get
    NonEmptyList.of(
      ResponsibleIndividualSet(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        responsibleIndividualName = responsibleIndividual.fullName.value,
        responsibleIndividualEmail = responsibleIndividual.emailAddress.value,
        riVerification.submissionId,
        riVerification.submissionInstance,
        cmd.code,
        requestingAdminEmail = requesterEmail
      ),
      ApplicationStateChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        app.state.name,
        State.PENDING_GATEKEEPER_APPROVAL,
        requestingAdminName = requesterName,
        requestingAdminEmail = requesterEmail
      )
    )
  }

  private def asEventsUpdate(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther, riVerification: ResponsibleIndividualUpdateVerification): NonEmptyList[UpdateApplicationEvent] = {
    val responsibleIndividual = riVerification.responsibleIndividual
    val requesterEmail = riVerification.requestingAdminEmail
    NonEmptyList.of(
      ResponsibleIndividualChanged(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = CollaboratorActor(requesterEmail),
        responsibleIndividualName = responsibleIndividual.fullName.value,
        responsibleIndividualEmail = responsibleIndividual.emailAddress.value,
        riVerification.submissionId,
        riVerification.submissionInstance,
        requestingAdminEmail = requesterEmail
      )
    )  
  }

  def process(app: ApplicationData, cmd: ChangeResponsibleIndividualToOther): CommandHandler.Result = {
    responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(cmd.code)).map(maybeRIVerification => {
      maybeRIVerification match {
        case Some(riVerificationToU: ResponsibleIndividualToUVerification) => validateToU(app, cmd, riVerificationToU) map { _ =>
          asEventsToU(app, cmd, riVerificationToU)
        }
        case Some(riVerificationUpdate: ResponsibleIndividualUpdateVerification) => validateUpdate(app, cmd, riVerificationUpdate) map { _ =>
          asEventsUpdate(app, cmd, riVerificationUpdate)
        }
        case _ => Validated.Invalid(NonEmptyChain.one(s"No responsibleIndividualVerification found for code ${cmd.code}"))
      }
    })
  }
}
