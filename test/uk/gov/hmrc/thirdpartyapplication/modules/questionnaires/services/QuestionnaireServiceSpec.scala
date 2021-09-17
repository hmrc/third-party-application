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
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.services.QuestionnaireService

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.QuestionnaireId
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.ReferenceId

class QuestionnaireServiceSpec extends AsyncHmrcSpec {
  trait Setup {
    val answersDAO = new AnswersToQuestionnaireDAO()
    val underTest = new QuestionnaireService(new QuestionnaireDAO(), answersDAO)
    val applicationId = ApplicationId.random

    def raise(id: QuestionnaireId): ReferenceId = {
      await(underTest.raiseQuestionnaire(applicationId, id)).right.get 
    }
  }

  "QuesionnaireService" when {
    "raiseQuestionnaire" should {
      "store new answers when given a valid questionnaire" in new Setup {
        val result = await(underTest.raiseQuestionnaire(applicationId, QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire.id)) 
        result shouldBe 'right

        val stored = result.toOption.flatMap(x => await(answersDAO.fetch(x)))
        stored.value.applicationId shouldBe applicationId
        stored.value.questionnaireId shouldBe QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire.id
      }

      "fail when given an invalid questionnaire" in new Setup {
        val result = await(underTest.raiseQuestionnaire(applicationId, QuestionnaireId("bobbins")))
        result shouldBe 'left
      }

      "store a second set of new answers when given a valid questionnaire/app for a second time" in new Setup {
        val result = await(underTest.raiseQuestionnaire(applicationId, QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire.id)) 
        result shouldBe 'right

        Thread.sleep(50)

        val result2 = await(underTest.raiseQuestionnaire(applicationId, QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire.id)) 
        result2 shouldBe 'right

        val stored1 = result.toOption.flatMap(x => await(answersDAO.fetch(x)))
        val stored2 = result2.toOption.flatMap(x => await(answersDAO.fetch(x)))
        stored1.value.applicationId shouldBe stored2.value.applicationId
        stored1.value.questionnaireId shouldBe stored2.value.questionnaireId
        stored1.value.referenceId should not be stored2.value.questionnaireId
        stored1.value.startedOn.getMillis should be < stored2.value.startedOn.getMillis
      }
    }

    "fetch" should {
      
      "find and return a valid answer to questionnaire" in new Setup {
        val r1 = raise(QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire.id)
       
        await(underTest.fetch(r1)) shouldBe 'right
      }
  
      "find and return failure due to missing reference id" in new Setup {
        await(underTest.fetch(ReferenceId.random)) shouldBe 'left
      }
    }
  }
}
