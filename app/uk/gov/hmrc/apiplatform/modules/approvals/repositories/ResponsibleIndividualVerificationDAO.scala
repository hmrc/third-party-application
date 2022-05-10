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

package uk.gov.hmrc.apiplatform.modules.approvals.repositories

import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerification
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId

import play.api.libs.json.Json
import play.api.libs.json.Json._
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ResponsibleIndividualVerificationDAO @Inject()(repo: ResponsibleIndividualVerificationRepository)(implicit ec: ExecutionContext) {

  private def byResponsibleIndividualVerificationId(id: ResponsibleIndividualVerificationId): (String, Json.JsValueWrapper) = ("id", id.value)

  def save(verification: ResponsibleIndividualVerification): Future[ResponsibleIndividualVerification] = {
    repo.insert(verification).map(_ => verification)
  }

  def fetch(id: ResponsibleIndividualVerificationId): Future[Option[ResponsibleIndividualVerification]] = {
    repo
    .find( byResponsibleIndividualVerificationId(id) )
    .map(_.headOption)
  }

  def delete(id: ResponsibleIndividualVerificationId): Future[Unit] = 
    repo
    .remove(byResponsibleIndividualVerificationId(id))
    .map(_ => ())
}
