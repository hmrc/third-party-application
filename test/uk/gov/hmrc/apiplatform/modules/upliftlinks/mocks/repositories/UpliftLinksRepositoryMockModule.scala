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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.repositories

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import scala.concurrent.Future.{failed, successful}
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.UpliftLinksRepository
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import reactivemongo.api.commands.WriteResult
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.json.Json

trait UpliftLinksRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseUpliftLinksRepositoryRepoMock {
    def aMock: UpliftLinksRepository

    object Insert {
      def thenReturn() =
        when(aMock.insert(*)(*)).thenReturn(successful(mock[WriteResult]))

      def verifyCalled() = 
          verify(aMock, atLeast(1)).insert(*[UpliftLink])(*)

    }

    object Find {
      def thenReturn(upliftLink : UpliftLink) = when(aMock.find(*)(*)).thenReturn(successful(List(upliftLink)))
      def thenReturnNothing = when(aMock.find(*)(*)).thenReturn(successful(List()))
    }
  }
  
  object UpliftLinksRepositoryMock extends BaseUpliftLinksRepositoryRepoMock {
    val aMock = mock[UpliftLinksRepository]
  }
}