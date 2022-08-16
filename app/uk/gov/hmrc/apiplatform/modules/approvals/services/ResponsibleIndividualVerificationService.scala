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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import cats.data.OptionT
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualToUVerification, ResponsibleIndividualUpdateVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationWithDetails
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{Standard, TermsOfUseAcceptance}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.{ExecutionContext, Future}

import javax.inject.Inject
import java.time.{Clock, LocalDateTime}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ResponsibleIndividual

class ResponsibleIndividualVerificationService @Inject() (
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    applicationService: ApplicationService,
    submissionService: SubmissionsService,
    emailConnector: EmailConnector,
    declineApprovalsService: DeclineApprovalsService,
    clock: Clock
  )(implicit ec: ExecutionContext
  ) extends BaseService(stateHistoryRepository, clock) with ApplicationLogger {

  def createNewToUVerification(applicationData: ApplicationData, submissionId: Submission.Id, submissionInstance: Int): Future[ResponsibleIndividualVerification] = {
    val verification = ResponsibleIndividualToUVerification(
      applicationId = applicationData.id,
      submissionId = submissionId,
      submissionInstance = submissionInstance,
      applicationName = applicationData.name,
      createdOn = LocalDateTime.now(clock)
    )
    responsibleIndividualVerificationRepository.save(verification)
  }

  def createNewUpdateVerification(applicationData: ApplicationData, submissionId: Submission.Id, submissionInstance: Int, responsibleIndividualName: String, responsibleIndividualEmail: String): Future[ResponsibleIndividualVerification] = {
    val responsibleIndividual = ResponsibleIndividual.build(
      responsibleIndividualName,
      responsibleIndividualEmail
    ) 
    val verification = ResponsibleIndividualUpdateVerification(
      applicationId = applicationData.id,
      submissionId = submissionId,
      submissionInstance = submissionInstance,
      applicationName = applicationData.name,
      createdOn = LocalDateTime.now(clock),
      responsibleIndividual = responsibleIndividual
    )
    responsibleIndividualVerificationRepository.save(verification)
  }

  def getVerification(code: String): Future[Option[ResponsibleIndividualVerification]] = {
    responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(code))
  }

  def accept(code: String): Future[Either[String, ResponsibleIndividualVerificationWithDetails]] = {

    def deriveNewAppDetails(existing: ApplicationData): ApplicationData = {
      existing.copy(
        state = existing.state.toPendingGatekeeperApproval(clock)
      )
    }

    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET               = EitherTHelper.make[String]
    val riVerificationId = ResponsibleIndividualVerificationId(code)

    logger.info(s"Start responsible individual accept ToU for code:${code}")

    (
      for {
        riVerification        <- ET.fromOptionF(responsibleIndividualVerificationRepository.fetch(riVerificationId), "responsibleIndividualVerification not found")
        originalApp           <- ET.fromOptionF(applicationRepository.fetch(riVerification.applicationId), s"Application with id ${riVerification.applicationId} not found")
        _                     <- ET.cond(originalApp.isPendingResponsibleIndividualVerification, (), "application not in state pendingResponsibleIndividualVerification")
        submitterEmail        <- ET.fromOption(originalApp.state.requestedByEmailAddress, "requestedByEmailAddress not found")
        submitterName         <- ET.fromOption(originalApp.state.requestedByName, "requestedByName not found")
        updatedApp             = deriveNewAppDetails(originalApp)
        savedApp              <- ET.liftF(applicationRepository.save(updatedApp))
        responsibleIndividual <- ET.fromOptionF(
                                   addTermsOfUseAcceptance(riVerification, savedApp).value,
                                   s"Unable to add Terms of Use acceptance to application with id ${riVerification.applicationId}"
                                 )
        _                     <- ET.liftF(writeStateHistory(originalApp, responsibleIndividual.emailAddress.value))
        _                     <- ET.liftF(responsibleIndividualVerificationRepository.delete(riVerificationId))
        _                      = logger.info(s"Responsible individual has successfully accepted ToU for appId:${riVerification.applicationId}, code:{$code}")
      } yield ResponsibleIndividualVerificationWithDetails(riVerification, responsibleIndividual, submitterName, submitterEmail)
    ).value
  }

  private def addTermsOfUseAcceptance(verification: ResponsibleIndividualVerification, appData: ApplicationData) = {
    import cats.implicits._
    appData.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => {
        val responsibleIndividual = importantSubmissionData.responsibleIndividual
        val acceptance            = TermsOfUseAcceptance(responsibleIndividual, LocalDateTime.now(clock), verification.submissionId, verification.submissionInstance)
        OptionT.liftF(applicationService.addTermsOfUseAcceptance(verification.applicationId, acceptance).map(_ => responsibleIndividual))
      }
      case _                                                      => OptionT.fromOption[Future](None)
    }
  }

  def decline(code: String): Future[Either[String, ResponsibleIndividualVerification]] = {

    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val ET               = EitherTHelper.make[String]
    val riVerificationId = ResponsibleIndividualVerificationId(code)

    logger.info(s"Start responsible individual decline ToU for code:${code}")

    (
      for {
        riVerification            <- ET.fromOptionF(responsibleIndividualVerificationRepository.fetch(riVerificationId), "responsibleIndividualVerification not found")
        originalApp               <- ET.fromOptionF(applicationRepository.fetch(riVerification.applicationId), s"Application with id ${riVerification.applicationId} not found")
        _                         <- ET.cond(originalApp.isPendingResponsibleIndividualVerification, (), "application not in state pendingResponsibleIndividualVerification")
        submission                <- ET.fromOptionF(submissionService.fetchLatest(riVerification.applicationId), "submission not found")
        importantSubmissionData   <- ET.fromOption(originalApp.importantSubmissionData, "expected application data is missing")
        ri                         = importantSubmissionData.responsibleIndividual
        responsibleIndividualEmail = ri.emailAddress.value
        requesterName             <- ET.fromOption(originalApp.state.requestedByName, "no requester name found")
        requesterEmail            <- ET.fromOption(originalApp.state.requestedByEmailAddress, "no requester email found")
        reason                     = "Responsible individual declined the terms of use."
        _                         <- ET.liftF(declineApprovalsService.decline(originalApp, submission, responsibleIndividualEmail, reason))
        _                         <- ET.liftF(emailConnector.sendResponsibleIndividualDeclined(ri.fullName.value, requesterEmail, originalApp.name, requesterName))
        _                          = logger.info(s"Responsible individual has successfully declined ToU for appId:${riVerification.applicationId}, code:{$code}")
      } yield riVerification
    ).value
  }

  private def writeStateHistory(snapshotApp: ApplicationData, name: String) =
    insertStateHistory(
      snapshotApp,
      PENDING_GATEKEEPER_APPROVAL,
      Some(PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION),
      name,
      COLLABORATOR,
      (a: ApplicationData) => applicationRepository.save(a)
    )
}
