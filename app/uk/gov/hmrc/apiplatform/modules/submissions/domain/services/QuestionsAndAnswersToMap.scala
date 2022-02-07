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

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

object QuestionsAndAnswersToMap {

  def stripSpacesAndCapitalise(inputText: String): String = {
    inputText.split("\\s").map(_.capitalize).mkString
  }

  def apply(submission: Submission) = {
     submission.latestInstance.answersToQuestions
    .map{ 
      case (questionId, answer) => (submission.findQuestion(questionId) -> answer)
    }
    .collect {
      case (Some(question), answer) => (stripSpacesAndCapitalise(question.wording.value) -> ActualAnswersAsText(answer))  
    }
  }
}
