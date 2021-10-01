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
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.SubscriptionRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.mocks.QuestionnaireDAOMockModule

class SubmissionsServiceSpec extends AsyncHmrcSpec with Inside {
  trait Setup 
    extends SubmissionsDAOMockModule 
    with ApplicationRepositoryMockModule
    with SubscriptionRepositoryMockModule
    with ApplicationTestData
    with TestData {

    val underTest = new SubmissionsService(new QuestionnaireDAO(), SubmissionsDAOMock.aMock, ApplicationRepoMock.aMock, SubscriptionRepoMock.aMock)
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
        }

      }
      "take an effective snapshot of current active questionnaires so that if they change the submission is unnaffected" in new Setup with QuestionnaireDAOMockModule {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        SubmissionsDAOMock.Save.thenReturn()
        override val underTest = new SubmissionsService(QuestionnaireDAOMock.aMock, SubmissionsDAOMock.aMock, ApplicationRepoMock.aMock, SubscriptionRepoMock.aMock)

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseStandardOnes()
        val result1 = await(underTest.create(applicationId))

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseChangedOnes()

        inside(result1.right.value) { case Submission(_, applicationId, _, groupings, questionnaireAnswers) =>
          applicationId shouldBe applicationId
          questionnaireAnswers.size shouldBe allQuestionnaires.size
          questionnaireAnswers.keySet shouldBe allQuestionnaires.map(_.id).toSet
        }

        val result2 = await(underTest.create(applicationId))
        inside(result2.right.value) { case Submission(_, applicationId, _, groupings, questionnaireAnswers) =>
          questionnaireAnswers.size shouldBe allQuestionnaires.size - 3 // The number from the dropped group
        }
      }
    }

    "fetchValidSubmissionHavingQuestionnaire" should {
      "return a submission when valid" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        
        await(underTest.fetchValidSubmissionHavingQuestionnaire(submissionId, questionnaireId).value) shouldBe Right(submission)
      }      

      "return a left when invalid" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        
        await(underTest.fetchValidSubmissionHavingQuestionnaire(submissionId, QuestionnaireId.random).value) shouldBe 'Left
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

    "getNextQuestion" should {
      "provide the next question when all data is present" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)

        ApplicationRepoMock.Fetch.thenReturn(anApplicationData(applicationId, testingState()))
        SubscriptionRepoMock.Fetch.thenReturn()

        val result = await(underTest.getNextQuestion(submissionId, questionnaireId))
        
        result.right.value.value shouldBe question
      }
    }
  }
}
