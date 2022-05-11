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

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerification
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationDAO
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Standard
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models.StateHistory
import uk.gov.hmrc.thirdpartyapplication.domain.models.Actor

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import javax.inject.Inject
import java.time.{Clock, LocalDateTime}


class ResponsibleIndividualVerificationService @Inject()(
    responsibleIndividualVerificationDao: ResponsibleIndividualVerificationDAO,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    val clock: Clock
 )(implicit ec: ExecutionContext) extends ApplicationLogger {

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
      std.importantSubmissionData.fold[Option[String]](None)(isd => Some(isd.responsibleIndividual.emailAddress.value))
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

    // Change state of application to PENDING_GATEKEEPER_APPROVAL and save timestamp. 
    // Also delete verification record.  To be done as part of a seperate story.
    (
      for {
        riVerification                     <- ET.fromOptionF(responsibleIndividualVerificationDao.fetch(riVerificationId), "responsibleIndividualVerification not found")
        originalApp                        <- ET.fromOptionF(applicationRepository.fetch(riVerification.applicationId), "application not found")
        _                                  <- ET.cond(originalApp.isPendingResponsibleIndividualVerification, (), "application not in state pendingResponsibleIndividualVerification")
        responsibleIndividualEmailAddress  <- ET.fromOption(getResponsibleIndividualEmail(originalApp), "unable to get responsible individual email address from application")
        updatedApp                         =  deriveNewAppDetails(originalApp)
        savedApp                           <- ET.liftF(applicationRepository.save(updatedApp))
        _                                  <- ET.liftF(writeStateHistory(originalApp, responsibleIndividualEmailAddress))
        _                                  <- ET.liftF(responsibleIndividualVerificationDao.delete(riVerificationId))
        _                                  =  logger.info(s"Responsible individual has successfully accepted ToU for appId:${riVerification.applicationId}")
      } yield riVerification
    ).value
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

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType, rollback: ApplicationData => Any): Future[StateHistory] = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState, changedAt = LocalDateTime.now(clock))
    stateHistoryRepository.insert(stateHistory)
    .andThen {
      case e: Failure[_] =>
        rollback(snapshotApp)
    }
  }

}
