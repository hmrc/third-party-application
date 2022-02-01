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
import org.joda.time.DateTime

object SubmissionStatusChanges {

  import Submission.Status._

  def appendNewState(newState: Submission.Status): Submission => Submission = (submission) => {
    require(isLegalTransition(submission.status, newState))
    val latestInstance = submission.latestInstance
    val updatedInstance = latestInstance.copy(statusHistory = newState :: latestInstance.statusHistory)
    submission.copy(instances = NonEmptyList(updatedInstance, submission.instances.tail))
  }

  def addNewInstanceToSubmission(timestamp: DateTime): Submission => Submission = (submission) => {
    val latestInstance = submission.latestInstance
    val originalCreator = latestInstance.statusHistory.last match {
      case Created(_, email) => email
      case _                 => "unknown"
    }

    val newInstance = latestInstance.copy(
      index = latestInstance.index+1,
      statusHistory = NonEmptyList.of(Created(timestamp, originalCreator))
    )
    submission.copy(instances = newInstance :: submission.instances)
  }

  def decline(timestamp: DateTime, name: String, reasons: String)(submission: Submission): Submission = {
    (
      appendNewState(Declined(timestamp, name, reasons)) andThen 
      addNewInstanceToSubmission(timestamp)
    )(submission)
  }

  def grant(timestamp: DateTime, name: String)(submission: Submission): Submission = {
    (
      appendNewState(Granted(timestamp, name))
    )(submission)
  }
}