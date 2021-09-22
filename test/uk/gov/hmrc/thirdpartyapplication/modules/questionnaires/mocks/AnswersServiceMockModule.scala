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

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.services.AnswersService
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

trait AnswersServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseAnswersServiceMock {
    def aMock: AnswersService

    object Fetch {
      def thenReturn(questionnaire: Questionnaire, answers: AnswersToQuestionnaire) =
        when(aMock.fetch(*[ReferenceId])).thenReturn(successful(Right((questionnaire, answers))))
        
      def thenFails(error: String) =
        when(aMock.fetch(*[ReferenceId])).thenReturn(successful(Left(error)))
    }

    object RaiseQuestionnaire {
      def thenReturn(referenceId: ReferenceId) =
        when(aMock.raiseQuestionnaire(*[ApplicationId], *[QuestionnaireId])).thenReturn(successful(Right(referenceId)))

      def thenFails(error: String) =
        when(aMock.raiseQuestionnaire(*[ApplicationId], *[QuestionnaireId])).thenReturn(successful(Left(error)))
    }
  }

  object AnswersServiceMock extends BaseAnswersServiceMock {
    val aMock = mock[AnswersService]
  }
}
