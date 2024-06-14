/*
 * Copyright 2024 HM Revenue & Customs
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

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.Future.successful

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ImportantSubmissionData, ResponsibleIndividual, TermsOfUseAcceptance}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission.Status.{Failed, Granted, Warnings}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.{FAILED, TERMS_OF_USE_V2, WARNINGS}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

trait SubmissionApprovalCommandsHandler extends CommandHandler {

  val termsOfUseInvitationRepository: TermsOfUseInvitationRepository
  val applicationRepository: ApplicationRepository

  def addTouAcceptanceIfNeeded(
      addTouAcceptance: Boolean,
      appWithoutTouAcceptance: StoredApplication,
      submission: Submission,
      timestamp: Instant,
      requesterName: String,
      requesterEmail: LaxEmailAddress
    ): Future[StoredApplication] = {
    if (addTouAcceptance) {
      val responsibleIndividual = ResponsibleIndividual.build(requesterName, requesterEmail.text)
      val acceptance            = TermsOfUseAcceptance(responsibleIndividual, timestamp, submission.id, submission.latestInstance.index)
      applicationRepository.addApplicationTermsOfUseAcceptance(appWithoutTouAcceptance.id, acceptance)
    } else {
      Future.successful(appWithoutTouAcceptance)
    }
  }

  def setTermsOfUseInvitationStatus(applicationId: ApplicationId, submission: Submission): Future[HasSucceeded] = {
    submission.status match {
      case Granted(_, _, _, _) => termsOfUseInvitationRepository.updateState(applicationId, TERMS_OF_USE_V2)
      case Warnings(_, _)      => termsOfUseInvitationRepository.updateState(applicationId, WARNINGS)
      case Failed(_, _)        => termsOfUseInvitationRepository.updateState(applicationId, FAILED)
      case _                   => successful(HasSucceeded)
    }
  }

  def updateStandardData(existingAccess: Access, importantSubmissionData: ImportantSubmissionData): Access = {
    existingAccess match {
      case s: Access.Standard => s.copy(importantSubmissionData = Some(importantSubmissionData))
      case _                  => existingAccess
    }
  }

  def createStateHistory(snapshotApp: StoredApplication, previousState: State, actor: Actor, timestamp: Instant): StateHistory =
    StateHistory(
      snapshotApp.id,
      snapshotApp.state.name,
      actor,
      Some(previousState),
      None,
      timestamp
    )
}
