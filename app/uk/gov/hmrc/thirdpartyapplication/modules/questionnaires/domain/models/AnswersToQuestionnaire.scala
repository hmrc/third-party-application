/*
 * Copyright 2021 HM Revenue & Customs
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

 package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models

import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import org.joda.time.DateTime
import java.util.UUID
import scala.collection.immutable.ListMap

@Deprecated
case class ReferenceId(value: String) extends AnyVal

object ReferenceId {
  implicit val format = play.api.libs.json.Json.valueFormat[ReferenceId]
  
  def random: ReferenceId = ReferenceId(UUID.randomUUID().toString())
}

sealed trait ActualAnswer
case class SingleChoiceAnswer(value: String) extends ActualAnswer
case class MultipleChoiceAnswer(values: Set[String]) extends ActualAnswer
case class TextAnswer(value: String) extends ActualAnswer

case class AnswersToQuestionnaire(
  referenceId: ReferenceId, 
  questionnaireId: QuestionnaireId, 
  applicationId: ApplicationId, 
  startedOn: DateTime,
  answers: ListMap[QuestionId, ActualAnswer]
)

case class SubmissionId(value: String) extends AnyVal
object SubmissionId {
  implicit val format = play.api.libs.json.Json.valueFormat[SubmissionId]
  
  def random: SubmissionId = SubmissionId(UUID.randomUUID().toString())
}

case class Submission(
  submissionId: SubmissionId,
  applicationId: ApplicationId,
  startedOn: DateTime
)