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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.services

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.mocks.SubmissionsDAOMockModule
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import cats.data.NonEmptyList
import org.scalatest.Inside
import uk.gov.hmrc.thirdpartyapplication.util.TestData


class SubmissionsServiceSpec extends AsyncHmrcSpec with Inside {
  trait Setup 
    extends SubmissionsDAOMockModule with TestData {

    val underTest = new SubmissionsService(new QuestionnaireDAO(), SubmissionsDAOMock.aMock)
  }

  "SubmissionsService" when {
    "create new submission" should {
      "store a submission for the application" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        SubmissionsDAOMock.Save.thenReturn()
        
        val result = await(underTest.create(applicationId))

        inside(result.right.value) { case Submission(_, applicationId, _, groupings, questionnaireAnswers) =>
          applicationId shouldBe applicationId
          questionnaireAnswers.size shouldBe allQuestionnaires.size
          questionnaireAnswers.keySet shouldBe allQuestionnaires.map(_.id).toSet

          groupings shouldBe groupIds
        }
      }
    }

    "recordAnswers" should {
      "records new answers when given a valid questionnaire" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        SubmissionsDAOMock.Update.thenReturn()

        val result = await(underTest.recordAnswers(submissionId, questionnaireId, questionId, NonEmptyList.of("Yes"))) 
        
        val out = result.right.value
        out.questionnaireAnswers(questionnaireId).get(questionId).value shouldBe SingleChoiceAnswer("Yes")
        SubmissionsDAOMock.Update.verifyCalled()
      }

      "fail when given an invalid questionnaire" in new Setup {
      }
    }
  }
}
