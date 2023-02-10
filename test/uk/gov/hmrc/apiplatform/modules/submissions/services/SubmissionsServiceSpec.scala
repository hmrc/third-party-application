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

package uk.gov.hmrc.apiplatform.modules.submissions.services

import cats.data.NonEmptyList
import org.scalatest.Inside

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services._
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.{SubmissionsDAOMockModule, _}
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{
  ApplicationApprovalRequestDeclined,
  ApplicationStateChanged,
  ResponsibleIndividualDidNotVerify
}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, State, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, _}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.Actors

class SubmissionsServiceSpec extends AsyncHmrcSpec with Inside with FixedClock {

  trait Setup
      extends SubmissionsDAOMockModule
      with ApplicationRepositoryMockModule
      with ContextServiceMockModule
      with ApplicationTestData
      with SubmissionsTestData
      with AsIdsHelpers {
    val underTest = new SubmissionsService(new QuestionnaireDAO(), SubmissionsDAOMock.aMock, ContextServiceMock.aMock, clock)
  }

  "SubmissionsService" when {
    "create new submission" should {
      "store a submission for the application" in new Setup {
        SubmissionsDAOMock.Save.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.create(applicationId, "bob@example.com"))

        inside(result.right.value) {
          case s @ Submission(_, applicationId, _, groupings, testQuestionIdsOfInterest, instances, _) =>
            applicationId shouldBe applicationId
            instances.head.answersToQuestions.size shouldBe 0
        }
      }

      "take an effective snapshot of current active questionnaires so that if they change the submission is unnaffected" in new Setup with QuestionnaireDAOMockModule {
        SubmissionsDAOMock.Fetch.thenReturn(aSubmission)
        SubmissionsDAOMock.Save.thenReturn()
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        override val underTest = new SubmissionsService(QuestionnaireDAOMock.aMock, SubmissionsDAOMock.aMock, ContextServiceMock.aMock, clock)

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseStandardOnes()
        val result1 = await(underTest.create(applicationId, "bob@example.com"))

        inside(result1.right.value) {
          case s @ Submission(_, applicationId, _, _, testQuestionIdsOfInterest, answersToQuestions, _) =>
            applicationId shouldBe applicationId
            s.allQuestionnaires.size shouldBe allQuestionnaires.size
        }

        QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseChangedOnes()

        val result2 = await(underTest.create(applicationId, "bob@example.com"))
        inside(result2.right.value) {
          case s @ Submission(_, applicationId, _, _, testQuestionIdsOfInterest, answersToQuestions, _) =>
            s.allQuestionnaires.size shouldBe allQuestionnaires.size - 1 // The number from the dropped group
        }
      }
    }

    "fetchLatest" should {
      "fetch latest submission for an application id" in new Setup {
        SubmissionsDAOMock.FetchLatest.thenReturn(aSubmission)
        ContextServiceMock.DeriveContext.willReturn(simpleContext)

        val result = await(underTest.fetchLatest(applicationId))

        result.value shouldBe aSubmission
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
        val completedAnswers: Submission.AnswersToQuestions = Map(Question.Id("q1") -> TextAnswer("ok"))
        val completeSubmission                              = aSubmission.copy(
          groups = NonEmptyList.of(
            GroupOfQuestionnaires(
              heading = "About your processes",
              links = NonEmptyList.of(
                Questionnaire(
                  id = Questionnaire.Id("79590bd3-cc0d-49d9-a14d-6fa5dfc73f39"),
                  label = Questionnaire.Label("Marketing your software"),
                  questions = NonEmptyList.of(
                    QuestionItem(
                      TextQuestion(
                        Question.Id("q1"),
                        Wording("Do you provide software as a service (SaaS)?"),
                        Some(Statement(
                          StatementText("SaaS is centrally hosted and is delivered on a subscription basis.")
                        )),
                        None,
                        None
                      )
                    )
                  )
                )
              )
            )
          )
        )
          .hasCompletelyAnsweredWith(completedAnswers)

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

        result.left.value shouldBe "Submission cannot be marked yet"
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

        val result = await(underTest.recordAnswers(submissionId, Question.Id.random, List("Yes")))

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

    "applyEvents" should {
      val now          = FixedClock.now
      val appId        = ApplicationId.random
      val submissionId = Submission.Id.random
      val reasons      = "reasons description"
      val code         = "5324763549732592387659238746"

      def buildApplicationApprovalRequestDeclinedEvent() =
        ApplicationApprovalRequestDeclined(
          UpdateApplicationEvent.Id.random,
          appId,
          now,
          Actors.Collaborator("requester@example.com"),
          "Mr New Ri",
          "ri@example.com",
          submissionId,
          0,
          reasons,
          "Mr Admin",
          "admin@example.com"
        )

      def buildResponsibleIndividualDidNotVerifyEvent() =
        ResponsibleIndividualDidNotVerify(
          UpdateApplicationEvent.Id.random,
          appId,
          now,
          Actors.Collaborator("requester@example.com"),
          "Mr New Ri",
          "ri@example.com",
          submissionId,
          0,
          code,
          "Mr Admin",
          "admin@example.com"
        )

      def buildApplicationStateChangedEvent() =
        ApplicationStateChanged(
          UpdateApplicationEvent.Id.random,
          appId,
          now,
          Actors.Collaborator("requester@example.com"),
          State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION,
          State.TESTING,
          "Mr Admin",
          "admin@example.com"
        )

      "decline a submission given an ApplicationApprovalRequestDeclined event" in new Setup {
        val event = buildApplicationApprovalRequestDeclinedEvent()

        SubmissionsDAOMock.Fetch.thenReturn(submittedSubmission)
        SubmissionsDAOMock.Update.thenReturn()

        val result = await(underTest.applyEvents(NonEmptyList.one(event)))

        val out = result.value
        out.instances.length shouldBe submittedSubmission.instances.length + 1
        out.instances.tail.head.status.isDeclined shouldBe true
        SubmissionsDAOMock.Update.verifyCalled()
      }

      "process many events including an ApplicationApprovalRequestDeclined event" in new Setup {
        val event1 = buildApplicationApprovalRequestDeclinedEvent()
        val event2 = buildResponsibleIndividualDidNotVerifyEvent()
        val event3 = buildApplicationStateChangedEvent()

        SubmissionsDAOMock.Fetch.thenReturn(submittedSubmission)
        SubmissionsDAOMock.Update.thenReturn()

        await(underTest.applyEvents(NonEmptyList.of(event1, event2, event3)))

        SubmissionsDAOMock.Update.verifyCalled()
      }
    }
  }
}
