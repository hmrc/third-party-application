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

package uk.gov.hmrc.apiplatform.modules.submissions.domain.services

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import cats.data.NonEmptyList

object SubmissionStatusChanges {

  import Submission.Status._

  def isLegalTransition(from: Submission.Status, to: Submission.Status): Boolean = (from, to) match {
    case (c: Created,   s: Submitted) => true
    case (s: Submitted, d: Declined)  => true
    case (s: Submitted, g: Granted)   => true
    case _                            => false
  }

  def appendNewState(newState: Submission.Status)(submission: Submission): Submission = {
    require(isLegalTransition(submission.status, newState))
    val latestInstance = submission.latestInstance
    val updatedInstance = latestInstance.copy(statusHistory = newState :: latestInstance.statusHistory)
    submission.copy(instances = NonEmptyList(updatedInstance, submission.instances.tail))
  }
}