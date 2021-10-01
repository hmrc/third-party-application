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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.mocks

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.Questionnaire
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.QuestionnaireId

import scala.concurrent.Future.successful

trait QuestionnaireDAOMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseQuestionnaireDAOMock {
    def aMock: QuestionnaireDAO

    object Fetch {
      def thenReturn(questionnaire: Option[Questionnaire]) =
        when(aMock.fetch(*[QuestionnaireId])).thenReturn(successful(questionnaire))
    }

    object ActiveQuestionnaireGroupings {
      def thenUseStandardOnes() = 
        when(aMock.fetchActiveGroupsOfQuestionnaires()).thenReturn(successful(QuestionnaireDAO.Questionnaires.activeQuestionnaireGroupings))
      def thenUseChangedOnes() = 
        when(aMock.fetchActiveGroupsOfQuestionnaires()).thenReturn(successful(QuestionnaireDAO.Questionnaires.activeQuestionnaireGroupings.drop(1)))
    }
  }

  object QuestionnaireDAOMock extends BaseQuestionnaireDAOMock {
    val aMock = mock[QuestionnaireDAO]
  }
}
