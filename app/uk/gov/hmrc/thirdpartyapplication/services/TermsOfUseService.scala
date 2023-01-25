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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.repository.TermsOfUseRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationResponse

@Singleton
class TermsOfUseService @Inject()(
  termsOfUseRepository: TermsOfUseRepository
)(
  implicit val ec: ExecutionContext
) extends ApplicationLogger {

  def createInvitation(id: ApplicationId): Future[Boolean] = termsOfUseRepository.create(TermsOfUseInvitation(id))
  def fetchInvitations(): Future[List[TermsOfUseInvitationResponse]] = {
    for {
      invitesF <- termsOfUseRepository.fetchAll()
      responsesF = invitesF.map(invite => TermsOfUseInvitationResponse(invite.applicationId, invite.createdOn))
    } yield responsesF
  }
}
