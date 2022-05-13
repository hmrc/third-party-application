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

import org.mongodb.scala.model.Filters.equal
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ResponsibleIndividualVerificationDAO @Inject()(repo: ResponsibleIndividualVerificationRepository)(implicit ec: ExecutionContext) {

  private lazy val collection = repo.collection

  def save(verification: ResponsibleIndividualVerification): Future[ResponsibleIndividualVerification] = {
    collection
      .insertOne(verification)
      .toFuture()
      .map(_ => verification)
  }

  def fetch(id: ResponsibleIndividualVerificationId): Future[Option[ResponsibleIndividualVerification]] = {
    collection
      .find(equal("id", id.value))
      .headOption()
  }

  def delete(id: ResponsibleIndividualVerificationId): Future[Long] = {
    collection
      .deleteOne(equal("id", id.value))
      .toFuture()
      .map(x => x.getDeletedCount)
  }
}
