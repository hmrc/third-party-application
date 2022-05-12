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
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId, ResponsibleIndividualVerificationWithDetails}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationDAO
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper

import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.domain.models.{Standard, TermsOfUseAcceptance}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import java.time.LocalDateTime

class ResponsibleIndividualVerificationService @Inject()(
    responsibleIndividualVerificationDao: ResponsibleIndividualVerificationDAO,
    applicationService: ApplicationService
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

  def accept(code: String): Future[Either[String, ResponsibleIndividualVerificationWithDetails]] = {
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[String]

    // TODO: Change state of application to PENDING_GATEKEEPER_APPROVAL and save timestamp.
    // Also delete verification record.  To be done as part of a seperate story.
    (
      for {
        riVerification        <- ET.fromOptionF(responsibleIndividualVerificationDao.fetch(ResponsibleIndividualVerificationId(code)), "responsibleIndividualVerification not found")
        appResponse           <- ET.fromOptionF(applicationService.fetch(riVerification.applicationId).value, s"Application with id ${riVerification.applicationId} not found")
        responsibleIndividual <- ET.fromOptionF(addTermsOfUseAcceptance(riVerification, appResponse).value, s"Unable to add Terms of Use acceptance to application with id ${riVerification.applicationId}")
        _                      =  logger.info(s"Responsible individual has successfully accepted ToU for appId:${riVerification.applicationId}")
      } yield ResponsibleIndividualVerificationWithDetails(riVerification, responsibleIndividual)
    ).value

  }

  private def addTermsOfUseAcceptance(verification: ResponsibleIndividualVerification, appResponse: ApplicationResponse) = {
    import cats.implicits._
    appResponse.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => {
        val responsibleIndividual = importantSubmissionData.responsibleIndividual
        val acceptance = TermsOfUseAcceptance(responsibleIndividual, LocalDateTime.now, verification.submissionId)
        OptionT.liftF(applicationService.addTermsOfUseAcceptance(verification.applicationId, acceptance).map(_ => responsibleIndividual))
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
}
