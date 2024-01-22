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

package uk.gov.hmrc.apiplatform.modules.submissions.domain.services

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

class QuestionsAndAnswersToMapSpec extends HmrcSpec {

  trait Setup extends SubmissionsTestData {

    val answersToQuestionsWithMissingIds: Map[Question.Id, ActualAnswer] = Map(
      (Question.Id.random                            -> TextAnswer("bad question")),
      (CustomersAuthorisingYourSoftware.question1.id -> TextAnswer("question 1")),
      (CustomersAuthorisingYourSoftware.question2.id -> TextAnswer("question 2"))
    )
    val submissionWithMissingQuestionIds                                 = Submission.updateLatestAnswersTo(answersToQuestionsWithMissingIds)(aSubmission)
  }

  "QuestionsAndAnswersToMap" should {
    "return a map of questions to answers" in new Setup {
      val answers: Map[Question.Id, ActualAnswer] = Map(
        (CustomersAuthorisingYourSoftware.question1.id -> TextAnswer("question 1")),
        (CustomersAuthorisingYourSoftware.question2.id -> TextAnswer("question 2"))
      )

      val map = QuestionsAndAnswersToMap(aSubmission.answeringWith(answers))
      map.size shouldBe 2
      map should contain("CustomersAuthorisingYourSoftware" -> "question 1")
      map should contain("ConfirmTheNameOfYourSoftware" -> "question 2")
    }

    "return a map of questions to answers omitting missing question ids" in new Setup {
      val answers: Map[Question.Id, ActualAnswer] = Map(
        (Question.Id.random                            -> TextAnswer("bad question")),
        (CustomersAuthorisingYourSoftware.question1.id -> TextAnswer("question 1")),
        (CustomersAuthorisingYourSoftware.question2.id -> TextAnswer("question 2"))
      )

      val map = QuestionsAndAnswersToMap(aSubmission.answeringWith(answers))
      map.size shouldBe 2
      map should contain("CustomersAuthorisingYourSoftware" -> "question 1")
      map should contain("ConfirmTheNameOfYourSoftware" -> "question 2")
    }
  }
}
