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
import org.joda.time.DateTime

object SubmissionStatusChanges {

  import Submission.Status._
  import Submission._

  val decline: (DateTime, String, String) => Submission => Submission = (timestamp, name, reasons) => {
    val addDeclinedStatus = addStatusHistory(Declined(timestamp, name, reasons))
    val addNewlyAnsweringInstance: Submission => Submission = (s) => addInstance(s.latestInstance.answersToQuestions, Answering(timestamp, true))(s)
    
    addDeclinedStatus andThen addNewlyAnsweringInstance
  }

  val grant: (DateTime, String) => Submission => Submission = (timestamp, name) => addStatusHistory(Granted(timestamp, name))

  val submit: (DateTime, String) => Submission => Submission = (timestamp, requestedBy) => addStatusHistory(Submitted(timestamp, requestedBy))
}