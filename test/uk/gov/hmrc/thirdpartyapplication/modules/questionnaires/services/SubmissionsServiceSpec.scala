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
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import org.joda.time._
import uk.gov.hmrc.time.DateTimeUtils
import scala.collection.immutable.ListMap
import cats.data.NonEmptyList
import org.scalatest.Inside

class SubmissionsServiceSpec extends AsyncHmrcSpec with Inside {
  trait Setup 
    extends SubmissionsDAOMockModule {

    val questionnaire = QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire
    val questionnaireId = questionnaire.id
    val questionId = questionnaire.questions.head.question.id
    val question2Id = questionnaire.questions.tail.head.question.id
    val submissionId = SubmissionId.random
    val applicationId = ApplicationId.random
    val answers = AnswersToQuestionnaire(questionnaire.id, ListMap.empty)
    val groups = QuestionnaireDAO.Questionnaires.activeQuestionnaireGroupings
    val submission = Submission(submissionId, applicationId, DateTimeUtils.now, groups, List(answers))

    val activeQuestionnaires = groups.flatMap(_.links)

    val underTest = new SubmissionsService(new QuestionnaireDAO(), SubmissionsDAOMock.aMock)
  }

  "SubmissionsService" when {
    "create new submission" should {
      "store a submission for the application" in new Setup {
        SubmissionsDAOMock.Save.thenReturn()

        val result = await(underTest.create(applicationId))

        inside(result.right.value) { case Submission(_, applicationId, _, groupings, answersToQuestionnaires) =>
          applicationId shouldBe applicationId
          answersToQuestionnaires.size shouldBe activeQuestionnaires.size
          answersToQuestionnaires.map(_.questionnaireId).toSet shouldBe activeQuestionnaires.toSet

          groupings shouldBe groups
        }
      }
    }
    // "raiseQuestionnaire" should {
    //   "store new answers when given a valid questionnaire" in new Setup {
    //     SubmissionsDAOMock.Create.thenReturn(referenceId)

    //     val result = await(underTest.raiseQuestionnaire(applicationId, questionnaireId)) 
    //     result shouldBe 'right

    //     SubmissionsDAOMock.Create.verifyCalled
    //   }

    //   "fail when given an invalid questionnaire" in new Setup {
    //     val result = await(underTest.raiseQuestionnaire(applicationId, QuestionnaireId("bobbins")))
    //     result shouldBe 'left
    //   }

    //   "store a second set of new answers when given a valid questionnaire/app for a second time" in new Setup {
    //     SubmissionsDAOMock.Create.thenReturn(referenceId)
    //     val fakeReferenceId2 = ReferenceId.random
    //     SubmissionsDAOMock.Create.thenReturn(fakeReferenceId2)

    //     val result = await(underTest.raiseQuestionnaire(applicationId, questionnaireId)) 
    //     result shouldBe 'right

    //     val result2 = await(underTest.raiseQuestionnaire(applicationId, questionnaireId)) 
    //     result2 shouldBe 'right

    //     SubmissionsDAOMock.Create.verifyCalledTwice()
    //   }
    // }

    // "fetch" should {
    //   "find and return a valid answer to questionnaire" in new Setup {
    //     val answers = Submissions(referenceId, questionnaireId, applicationId, DateTimeUtils.now, ListMap.empty)
    //     SubmissionsDAOMock.Fetch.thenReturn(referenceId)(Some(answers))

    //     val result = await(underTest.fetch(referenceId))
    //     result shouldBe 'right
    //     val (q, a) = result.right.get
        
    //     q shouldBe QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire
    //     a shouldBe answers
    //   }
  
    //   "find and return failure due to missing reference id in answers collection" in new Setup {
    //     SubmissionsDAOMock.Fetch.thenReturn(referenceId)(None)

    //     await(underTest.fetch(referenceId)) shouldBe 'left
    //   }
    // }

    // "recordAnswer" should {
    //   "add to an existing answers map" in new Setup {
    //     val answers = Submissions(referenceId, questionnaireId, applicationId, DateTimeUtils.now, ListMap.empty)
    //     SubmissionsDAOMock.Fetch.thenReturn(referenceId)(Some(answers))
    //     SubmissionsDAOMock.Save.thenReturn()

    //     val atq = await(underTest.recordAnswer(referenceId, questionId, NonEmptyList.of("Yes")))

    //     atq.right.value.answers should contain (questionId -> SingleChoiceAnswer("Yes")) 
    //   }

    //   "overwrite an existing answer in the answers map" in new Setup {
    //     val answers = Submissions(
    //       referenceId, 
    //       questionnaireId, 
    //       applicationId, 
    //       DateTimeUtils.now, 
    //       ListMap(questionId -> SingleChoiceAnswer("No"))
    //     )
    //     SubmissionsDAOMock.Fetch.thenReturn(referenceId)(Some(answers))
    //     SubmissionsDAOMock.Save.thenReturn()

    //     val atq = await(underTest.recordAnswer(referenceId, questionId, NonEmptyList.of("Yes")))

    //     atq.right.value.answers should contain (questionId -> SingleChoiceAnswer("Yes")) 
    //   }

    //   "add a new answer in a populated answers map" in new Setup {
    //     val answers = Submissions(
    //       referenceId, 
    //       questionnaireId, 
    //       applicationId, 
    //       DateTimeUtils.now, 
    //       ListMap(question2Id -> SingleChoiceAnswer("No"))
    //     )
    //     SubmissionsDAOMock.Fetch.thenReturn(referenceId)(Some(answers))
    //     SubmissionsDAOMock.Save.thenReturn()

    //     val atq = await(underTest.recordAnswer(referenceId, questionId, NonEmptyList.of("Yes")))

    //     atq.right.value.answers should contain (questionId -> SingleChoiceAnswer("Yes")) 
    //   }
    // }
  }
}
