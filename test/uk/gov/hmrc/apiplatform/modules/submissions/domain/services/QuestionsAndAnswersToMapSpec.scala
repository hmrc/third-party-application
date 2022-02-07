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

import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO
import cats.data.NonEmptyList

class QuestionsAndAnswersToMapSpec extends HmrcSpec {
  trait Setup extends SubmissionsTestData {
    val answersToQuestionsWithMissingIds: Map[QuestionId, ActualAnswer] = Map(
      (QuestionId.random -> TextAnswer("bad question")),
      (QuestionnaireDAO.Questionnaires.CustomersAuthorisingYourSoftware.question1.id -> TextAnswer("question 1")),
      (QuestionnaireDAO.Questionnaires.CustomersAuthorisingYourSoftware.question2.id -> TextAnswer("question 2"))
    )
    val submissionWithMissingQuestionIds = aSubmission.copy(instances = NonEmptyList.of(Submission.Instance(0, answersToQuestionsWithMissingIds, NonEmptyList.of(initialStatus))))
  }

  "QuestionsAndAnswersToMap" should {
    "return a map of questions to answers" in new Setup {
      val answeredSubmission = buildAnsweredSubmission()
      val map = QuestionsAndAnswersToMap(answeredSubmission)
      map.size shouldBe answeredSubmission.latestInstance.answersToQuestions.size
    }
    
    "return a map of questions to answers omitting missing question ids" in new Setup {
      val map = QuestionsAndAnswersToMap(submissionWithMissingQuestionIds)

      map.size shouldBe 2
      map should contain ("CustomersAuthorisingYourSoftware" -> "question 1")
      map should contain ("ConfirmTheNameOfYourSoftware" -> "question 2")
    }
  }
}
