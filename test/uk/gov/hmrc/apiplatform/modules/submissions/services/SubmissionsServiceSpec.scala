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

package uk.gov.hmrc.apiplatform.modules.submissions.services

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsDAOMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import org.scalatest.Inside
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.apiplatform.modules.submissions.mocks._
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO.Questionnaires._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services._
import cats.data.NonEmptyList
import uk.gov.hmrc.time.DateTimeUtils

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
        
        val result = await(underTest.create(applicationId, "bob@example.com"))

        inside(result.right.value) { 
          case s @ ExtendedSubmission(Submission(_, applicationId, _, groupings, QuestionnaireDAO.questionIdsOfInterest, instances), progress) =>
            applicationId shouldBe applicationId
            instances.head.answersToQuestions.size shouldBe 0
            progress.size shouldBe s.submission.allQuestionnaires.size
            progress.get(DevelopmentPractices.questionnaire.id).value shouldBe QuestionnaireProgress(QuestionnaireState.NotStarted, DevelopmentPractices.questionnaire.questions.asIds)
            progress.get(FraudPreventionHeaders.questionnaire.id).value shouldBe QuestionnaireProgress(QuestionnaireState.NotApplicable, List.empty[QuestionId])
          }
      }
      
      "take an effective snapshot of current active questionnaires so that if they change the submission is unnaffected" in new Setup with QuestionnaireDAOMockModule {
        SubmissionsDAOMock.Fetch.thenReturn(aSubmission)
        SubmissionsDAOMock.Save.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)
        
        override val underTest = new SubmissionsService(QuestionnaireDAOMock.aMock, SubmissionsDAOMock.aMock, ContextServiceMock.aMock)

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseStandardOnes()
        val result1 = await(underTest.create(applicationId, "bob@example.com"))
        
        inside(result1.right.value) {
          case s @ ExtendedSubmission(Submission(_, applicationId, _, groupings, QuestionnaireDAO.questionIdsOfInterest, answersToQuestions), progress) =>
            applicationId shouldBe applicationId
            s.submission.allQuestionnaires.size shouldBe allQuestionnaires.size
          }

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseChangedOnes()

        val result2 = await(underTest.create(applicationId, "bob@example.com"))
        inside(result2.right.value) { 
          case s @ ExtendedSubmission(Submission(_, applicationId, _, groupings, QuestionnaireDAO.questionIdsOfInterest, answersToQuestions), progress) =>
            s.submission.allQuestionnaires.size shouldBe allQuestionnaires.size - 2 // The number from the dropped group
          }
      }
    }

    "fetchLatest" should {
      "fetch latest submission for an application id" in new Setup {
        SubmissionsDAOMock.FetchLatest.thenReturn(aSubmission)
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetchLatest(applicationId))

        result.value.submission shouldBe aSubmission
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
        SubmissionsDAOMock.Fetch.thenReturn(aSubmission)
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetch(submissionId))

        result.value.submission shouldBe aSubmission
      }

      "fail when given an invalid application id" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturnNothing()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetch(submissionId))

        result shouldBe None
      }
    }
    
    "fetchLatestMarkedSubmission" should {
      "fetch latest marked submission for id" in new Setup {
        val completedAnswers: Submission.AnswersToQuestions = Map(QuestionId("q1") -> TextAnswer("ok"))
        val completeSubmission = aSubmission.copy(
          groups = NonEmptyList.of(
            GroupOfQuestionnaires(
              heading = "About your processes",
              links = NonEmptyList.of(
                Questionnaire(
                  id = QuestionnaireId("79590bd3-cc0d-49d9-a14d-6fa5dfc73f39"),
                  label = Label("Marketing your software"),
                  questions = NonEmptyList.of(
                    QuestionItem(
                      TextQuestion(
                        QuestionId("q1"), 
                        Wording("Do you provide software as a service (SaaS)?"),
                        Statement(
                          StatementText("SaaS is centrally hosted and is delivered on a subscription basis.")
                        ), 
                        None
                      ) 
                    )
                  )
                )
              )             
            )
          ),
          instances = NonEmptyList.of(
            Submission.Instance(
              index = 0,
              answersToQuestions = completedAnswers,
              statusHistory = NonEmptyList.of(
                Submission.Status.Created(DateTimeUtils.now, "user@example.com")
              )
            )
          )
        )
        
        SubmissionsDAOMock.FetchLatest.thenReturn(completeSubmission)
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetchLatestMarkedSubmission(applicationId))

        result.right.value.submission shouldBe completeSubmission
      }

      "fail when given an invalid application id" in new Setup {
        SubmissionsDAOMock.FetchLatest.thenReturnNothing()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetchLatestMarkedSubmission(applicationId))

        result.left.value shouldBe "No such application submission"
      }

      "fail when given a valid application that is not completed" in new Setup {
        SubmissionsDAOMock.FetchLatest.thenReturn(aSubmission)
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetchLatestMarkedSubmission(applicationId))

        result.left.value shouldBe "Submission is not complete"
      }
    }

    "recordAnswers" should {
      "records new answers when given a valid question" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(aSubmission)
        SubmissionsDAOMock.Update.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.recordAnswers(submissionId, questionId, List("Yes")))
        
        val out = result.right.value
        out.submission.latestInstance.answersToQuestions.get(questionId).value shouldBe SingleChoiceAnswer("Yes")
        SubmissionsDAOMock.Update.verifyCalled()
      }

      "records new answers when given a valid optional question" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(aSubmission)
        SubmissionsDAOMock.Update.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.recordAnswers(submissionId, optionalQuestionId, List.empty))
        
        val out = result.right.value
        out.submission.latestInstance.answersToQuestions.get(optionalQuestionId).value shouldBe NoAnswer
        SubmissionsDAOMock.Update.verifyCalled()
      }

      "fail when given an invalid question" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(aSubmission)
        SubmissionsDAOMock.Update.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.recordAnswers(submissionId, QuestionId.random, List("Yes")))

        result shouldBe 'left
      }

      "fail when given a optional answer to non optional question" in new Setup {
        SubmissionsDAOMock.Fetch.thenReturn(aSubmission)
        SubmissionsDAOMock.Update.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.recordAnswers(submissionId, questionId, List.empty)) 

        result shouldBe 'left
      }
    }
  }
}