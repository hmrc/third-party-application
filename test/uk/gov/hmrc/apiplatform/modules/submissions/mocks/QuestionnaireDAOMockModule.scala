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

package uk.gov.hmrc.apiplatform.modules.submissions.mocks

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.QuestionnaireDao
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Questionnaire

import scala.concurrent.Future.successful
import cats.data.NonEmptyList
import uk.gov.hmrc.apiplatform.modules.submissions.QuestionnaireTestData

trait QuestionnaireDAOMockModule extends MockitoSugar with ArgumentMatchersSugar with QuestionnaireTestData {
  protected trait BaseQuestionnaireDAOMock {
    def aMock: QuestionnaireDao

    object Fetch {
      def thenReturn(questionnaire: Option[Questionnaire]) =
        when(aMock.fetch(*[Questionnaire.Id])).thenReturn(successful(questionnaire))
    }

    object ActiveQuestionnaireGroupings {
      def thenUseStandardOnes() = 
        when(aMock.fetchActiveGroupsOfQuestionnaires()).thenReturn(successful(testGroups))
      def thenUseChangedOnes() = 
        when(aMock.fetchActiveGroupsOfQuestionnaires()).thenReturn(successful(NonEmptyList.fromListUnsafe(testGroups.tail)))
    }
  }

  object QuestionnaireDAOMock extends BaseQuestionnaireDAOMock {
    val aMock = mock[QuestionnaireDao]
  }
}
