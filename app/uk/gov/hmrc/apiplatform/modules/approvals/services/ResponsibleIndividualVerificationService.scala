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
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationDAO
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{Standard, TermsOfUseAcceptance}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject
import java.time.{Clock, LocalDateTime}


class ResponsibleIndividualVerificationService @Inject()(
    responsibleIndividualVerificationDao: ResponsibleIndividualVerificationDAO,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    applicationService: ApplicationService,
    clock: Clock
 )(implicit ec: ExecutionContext) extends BaseService(stateHistoryRepository, clock) with ApplicationLogger {

  def createNewVerification(applicationData: ApplicationData, submissionId: Submission.Id, submissionInstance: Int) = {
    val verification = ResponsibleIndividualVerification(
      applicationId = applicationData.id,
      submissionId = submissionId,
      submissionInstance = submissionInstance,
      applicationName = applicationData.name
    )
    responsibleIndividualVerificationDao.save(verification)
  }

  def getVerification(code: String): Future[Option[ResponsibleIndividualVerification]] = {
    responsibleIndividualVerificationDao.fetch(ResponsibleIndividualVerificationId(code))
  }

  def accept(code: String): Future[Either[String, ResponsibleIndividualVerification]] = {

    def getResponsibleIndividualEmailFromStdApp(std: Standard): Option[String] = {
      std.importantSubmissionData.map(isd => isd.responsibleIndividual.emailAddress.value)
    }

    def getResponsibleIndividualEmail(app: ApplicationData): Option[String] = {
      app.access match {
        case std: Standard => getResponsibleIndividualEmailFromStdApp(std)
        case _ => None
      }
    }

    def deriveNewAppDetails(existing: ApplicationData): ApplicationData = {
      existing.copy(
        state = existing.state.toPendingGatekeeperApproval(clock) 
      )
    }


    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[String]
    val riVerificationId = ResponsibleIndividualVerificationId(code)

    (
      for {
        riVerification                     <- ET.fromOptionF(responsibleIndividualVerificationDao.fetch(riVerificationId), "responsibleIndividualVerification not found")
        originalApp                        <- ET.fromOptionF(applicationRepository.fetch(riVerification.applicationId), s"Application with id ${riVerification.applicationId} not found")
        _                                  <- ET.cond(originalApp.isPendingResponsibleIndividualVerification, (), "application not in state pendingResponsibleIndividualVerification")
        responsibleIndividualEmailAddress  <- ET.fromOption(getResponsibleIndividualEmail(originalApp), "unable to get responsible individual email address from application")
        updatedApp                         =  deriveNewAppDetails(originalApp)
        savedApp                           <- ET.liftF(applicationRepository.save(updatedApp))
        _                                  <- ET.liftF(addTermsOfUseAcceptance(riVerification, updatedApp).value)
        _                                  <- ET.liftF(writeStateHistory(originalApp, responsibleIndividualEmailAddress))
        _                                  <- ET.liftF(responsibleIndividualVerificationDao.delete(riVerificationId))
        _                                  =  logger.info(s"Responsible individual has successfully accepted ToU for appId:${riVerification.applicationId}")
      } yield riVerification
    ).value

  }

  private def addTermsOfUseAcceptance(verification: ResponsibleIndividualVerification, appResponse: ApplicationData) = {
    import cats.implicits._
    appResponse.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => {
        val responsibleIndividual = importantSubmissionData.responsibleIndividual
        val acceptance = TermsOfUseAcceptance(responsibleIndividual, LocalDateTime.now, verification.submissionId)
        OptionT.liftF(applicationService.addTermsOfUseAcceptance(verification.applicationId, acceptance))
      }
      case _ => OptionT.fromOption[Future](None)
    }
  }


  def decline(code: String): Future[Either[String, ResponsibleIndividualVerification]] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[String]

    // TODO: Decline the request, with an appropriate reason. 
    // Also delete verification record.  To be done as part of a seperate story.
    (
      for {
        riVerification <- ET.fromOptionF(responsibleIndividualVerificationDao.fetch(ResponsibleIndividualVerificationId(code)), "responsibleIndividualVerification not found")
        _              =  logger.info(s"Responsible individual has successfully declined ToU for appId:${riVerification.applicationId}")
      } yield riVerification
    ).value
  }

  private def writeStateHistory(snapshotApp: ApplicationData, name: String) = 
    insertStateHistory(snapshotApp, PENDING_GATEKEEPER_APPROVAL, Some(PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION), name, COLLABORATOR, (a: ApplicationData) => applicationRepository.save(a))
}
