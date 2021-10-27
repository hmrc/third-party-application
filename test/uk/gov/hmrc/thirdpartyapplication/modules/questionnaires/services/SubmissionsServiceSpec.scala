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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.services

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks.SubmissionsDAOMockModule
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import org.scalatest.Inside
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories.QuestionnaireDAO.Questionnaires._
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services._

class SubmissionsServiceSpec extends AsyncHmrcSpec with Inside {
  trait Setup 
    extends SubmissionsDAOMockModule 
    with ApplicationRepositoryMockModule
    with ContextServiceMockModule
    with ApplicationTestData
    with SubmissionsTestData
    with AsIdsHelpers {

    val underTest = new SubmissionsService(new QuestionnaireDAO(), SubmissionsDAOMock.aMock, ContextServiceMock.aMock)
  }

  "SubmissionsService" when {
    "create new submission" should {
      "store a submission for the application" in new Setup {
        SubmissionsDAOMock.Save.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)
        
        val result = await(underTest.create(applicationId))

        inside(result.right.value) { 
          case s @ Submission(_, applicationId, _, groupings, answersToQuestions, progress) =>
            applicationId shouldBe applicationId
            answersToQuestions.size shouldBe 0
            progress.size shouldBe s.allQuestionnaires.size
            progress.get(DevelopmentPractices.questionnaire.id).value shouldBe QuestionnaireProgress(NotStarted, DevelopmentPractices.questionnaire.questions.asIds)
            progress.get(FraudPreventionHeaders.questionnaire.id).value shouldBe QuestionnaireProgress(NotApplicable, List.empty[QuestionId])
          }
      }
      
      "take an effective snapshot of current active questionnaires so that if they change the submission is unnaffected" in new Setup with QuestionnaireDAOMockModule {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        SubmissionsDAOMock.Save.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)
        
        override val underTest = new SubmissionsService(QuestionnaireDAOMock.aMock, SubmissionsDAOMock.aMock, ContextServiceMock.aMock)

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseStandardOnes()
        val result1 = await(underTest.create(applicationId))
        
        inside(result1.right.value) {
          case sub @ Submission(_, applicationId, _, groupings, answersToQuestions, progress) =>
            applicationId shouldBe applicationId
            sub.allQuestionnaires.size shouldBe allQuestionnaires.size
          }

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseChangedOnes()

        val result2 = await(underTest.create(applicationId))
        inside(result2.right.value) { 
          case sub @ Submission(_, applicationId, _, groupings, answersToQuestions, progress) =>
            sub.allQuestionnaires.size shouldBe allQuestionnaires.size - 3 // The number from the dropped group
          }
      }
    }

    "fetchLatest" should {
      "fetch latest submission for an application id" in new Setup {
        SubmissionsDAOMock.FetchLatest.thenReturn(submission)
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetchLatest(applicationId))

        result.value shouldBe submission
      }

      "fail when given an invalid application id" in new Setup {
        SubmissionsDAOMock.FetchLatest.thenReturnNothing()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetchLatest(applicationId))

        result shouldBe None
      }
    }

  
    "fetch" should {
      "fetch latest submission for id" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetch(submissionId))

        result.value shouldBe submission
      }

      "fail when given an invalid application id" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturnNothing()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetch(submissionId))

        result shouldBe None
      }
    }
    
    "recordAnswers" should {
      "records new answers when given a valid question" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        SubmissionsDAOMock.Update.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.recordAnswers(submissionId, questionId, NonEmptyList.of("Yes"))) 
        
        val out = result.right.value
        out.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("Yes")
        SubmissionsDAOMock.Update.verifyCalled()
      }

      "fail when given an invalid question" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(submission)
        SubmissionsDAOMock.Update.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.recordAnswers(submissionId, QuestionId.random, NonEmptyList.of("Yes"))) 

        result shouldBe 'left
      }
    }
  }
}
