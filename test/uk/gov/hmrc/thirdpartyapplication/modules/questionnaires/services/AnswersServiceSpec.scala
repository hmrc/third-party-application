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
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.mocks.AnswersToQuestionnaireDAOMockModule
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.QuestionnaireId
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.ReferenceId
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.AnswersToQuestionnaire
import org.joda.time._
import uk.gov.hmrc.time.DateTimeUtils
import scala.collection.immutable.ListMap

class AnswersServiceSpec extends AsyncHmrcSpec {
  trait Setup extends AnswersToQuestionnaireDAOMockModule{
    val underTest = new AnswersService(new QuestionnaireDAO(), AnswersToQuestionnaireDAOMock.aMock)
    val applicationId = ApplicationId.random
    val fakeReferenceId = ReferenceId.random
    val questionnaireId = QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire.id

    def raise(id: QuestionnaireId): ReferenceId = {
      await(underTest.raiseQuestionnaire(applicationId, id)).right.get 
    }
  }

  "AnswersService" when {
    "raiseQuestionnaire" should {
      "store new answers when given a valid questionnaire" in new Setup {
        AnswersToQuestionnaireDAOMock.Create.thenReturn(fakeReferenceId)

        val result = await(underTest.raiseQuestionnaire(applicationId, questionnaireId)) 
        result shouldBe 'right

        AnswersToQuestionnaireDAOMock.Create.verifyCalled
      }

      "fail when given an invalid questionnaire" in new Setup {
        val result = await(underTest.raiseQuestionnaire(applicationId, QuestionnaireId("bobbins")))
        result shouldBe 'left
      }

      "store a second set of new answers when given a valid questionnaire/app for a second time" in new Setup {
        AnswersToQuestionnaireDAOMock.Create.thenReturn(fakeReferenceId)
        val fakeReferenceId2 = ReferenceId.random
        AnswersToQuestionnaireDAOMock.Create.thenReturn(fakeReferenceId2)

        val result = await(underTest.raiseQuestionnaire(applicationId, questionnaireId)) 
        result shouldBe 'right

        val result2 = await(underTest.raiseQuestionnaire(applicationId, questionnaireId)) 
        result2 shouldBe 'right

        AnswersToQuestionnaireDAOMock.Create.verifyCalledTwice()
      }
    }

    "fetch" should {
      
      "find and return a valid answer to questionnaire" in new Setup {
        val answers = AnswersToQuestionnaire(fakeReferenceId, questionnaireId, applicationId, DateTimeUtils.now, ListMap.empty)
        AnswersToQuestionnaireDAOMock.Fetch.thenReturn(fakeReferenceId)(Some(answers))

        val result = await(underTest.fetch(fakeReferenceId))
        result shouldBe 'right
        val (q, a) = result.right.get
        
        q shouldBe QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire
        a shouldBe answers
      }
  
      "find and return failure due to missing reference id in answers collection" in new Setup {
        AnswersToQuestionnaireDAOMock.Fetch.thenReturn(fakeReferenceId)(None)

        await(underTest.fetch(fakeReferenceId)) shouldBe 'left
      }
    }
  }
}
