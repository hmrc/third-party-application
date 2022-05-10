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
import scala.concurrent.{ExecutionContext, Future}
import javax.inject.Inject
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

class ResponsibleIndividualVerificationService @Inject()(
    responsibleIndividualVerificationDao: ResponsibleIndividualVerificationDAO
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
    import cats.implicits._
    import cats.instances.future.catsStdInstancesForFuture

    val ET = EitherTHelper.make[String]

    // TODO: Change state of application to PENDING_GATEKEEPER_APPROVAL and save timestamp. 
    // Also delete verification record.  To be done as part of a seperate story.
    (
      for {
        riVerification <- ET.fromOptionF(responsibleIndividualVerificationDao.fetch(ResponsibleIndividualVerificationId(code)), "responsibleIndividualVerification not found")
        _              =  logger.info(s"Responsible individual has successfully accepted ToU for appId:${riVerification.applicationId}")
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
}
