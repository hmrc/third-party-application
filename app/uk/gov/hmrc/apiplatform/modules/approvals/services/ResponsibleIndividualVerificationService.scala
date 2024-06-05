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

package uk.gov.hmrc.apiplatform.modules.approvals.services

import java.time.{Clock, Instant}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationId
}
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository

class ResponsibleIndividualVerificationService @Inject() (
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    stateHistoryRepository: StateHistoryRepository,
    clock: Clock
  )(implicit ec: ExecutionContext
  ) extends BaseService(stateHistoryRepository, clock) with ApplicationLogger {

  def createNewToUVerification(applicationData: StoredApplication, submissionId: SubmissionId, submissionInstance: Int): Future[ResponsibleIndividualVerification] = {
    val verification = ResponsibleIndividualToUVerification(
      applicationId = applicationData.id,
      submissionId = submissionId,
      submissionInstance = submissionInstance,
      applicationName = applicationData.name,
      createdOn = Instant.now(clock)
    )
    responsibleIndividualVerificationRepository.save(verification)
  }

  def createNewTouUpliftVerification(
      applicationData: StoredApplication,
      submissionId: SubmissionId,
      submissionInstance: Int,
      requestedByName: String,
      requestedByEmailAddress: LaxEmailAddress
    ): Future[ResponsibleIndividualVerification] = {
    val verification = ResponsibleIndividualTouUpliftVerification(
      applicationId = applicationData.id,
      submissionId = submissionId,
      submissionInstance = submissionInstance,
      applicationName = applicationData.name,
      createdOn = Instant.now(clock),
      requestingAdminName = requestedByName,
      requestingAdminEmail = requestedByEmailAddress
    )
    responsibleIndividualVerificationRepository.save(verification)
  }

  def getVerification(code: String): Future[Option[ResponsibleIndividualVerification]] = {
    responsibleIndividualVerificationRepository.fetch(ResponsibleIndividualVerificationId(code))
  }
}
