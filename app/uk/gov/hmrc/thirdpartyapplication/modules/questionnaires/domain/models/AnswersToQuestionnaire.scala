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

sealed trait Answer
case class SingleChoiceAnswer(value: String) extends Answer
case class MultipleChoiceAnswer(values: Set[String]) extends Answer
case class TextAnswer(value: String) extends Answer

case class AnswersToQuestionnaire(
  referenceId: ReferenceId, 
  questionnaireId: QuestionnaireId, 
  applicationId: ApplicationId, 
  startedOn: DateTime,
  answers: Map[QuestionId, Answer]
)

case class ReferenceId(value: String) extends AnyVal

object ReferenceId {
  def random: ReferenceId = ReferenceId(UUID.randomUUID().toString())
}