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

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories.AnswersToQuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

trait AnswersToQuestionnaireDAOMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseAnswersToQuestionnaireDAOMock {
    def aMock: AnswersToQuestionnaireDAO

    object Fetch {
      def thenReturn(referenceId: ReferenceId)(answers: Option[AnswersToQuestionnaire]) =
        when(aMock.fetch(eqTo[ReferenceId](referenceId))).thenReturn(successful(answers))
    }

    object FindAll {
      ???
    }

    object FindLatest {
      ???
    }

    object Save {
      ???
    }

    object Create {
      def thenReturn(referenceId: ReferenceId) =
        when(aMock.create(*[ApplicationId], *[QuestionnaireId])).thenReturn(successful(referenceId))

      def verifyCalled() = 
        verify(aMock, atLeast(1)).create(*[ApplicationId], *[QuestionnaireId])

      def verifyCalledTwice() =
        verify(aMock, times(2)).create(*[ApplicationId], *[QuestionnaireId])
    }
  }

  object AnswersToQuestionnaireDAOMock extends BaseAnswersToQuestionnaireDAOMock {
    val aMock = mock[AnswersToQuestionnaireDAO]
  }
}
