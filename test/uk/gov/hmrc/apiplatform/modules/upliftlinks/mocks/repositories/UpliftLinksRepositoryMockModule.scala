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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks.repositories

import scala.concurrent.Future
import scala.concurrent.Future.successful

import org.mockito.stubbing.ScalaOngoingStubbing
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import uk.gov.hmrc.apiplatform.modules.upliftlinks.repositories.UpliftLinksRepository

trait UpliftLinksRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseUpliftLinksRepositoryRepoMock {
    def aMock: UpliftLinksRepository

    object Insert {

      def thenReturn(upliftLink: UpliftLink): ScalaOngoingStubbing[Future[UpliftLink]] =
        when(aMock.insert(*)).thenReturn(Future.successful(upliftLink))

      def verifyCalled(): Future[UpliftLink] = verify(aMock, atLeast(1)).insert(*[UpliftLink])

    }

    object Find {

      def thenReturn(upliftLink: UpliftLink): ScalaOngoingStubbing[Future[Option[ApplicationId]]] =
        when(aMock.find(*[ApplicationId])).thenReturn(Future.successful(Some(upliftLink.sandboxApplicationId)))

      def thenReturnNothing: ScalaOngoingStubbing[Future[Option[ApplicationId]]] =
        when(aMock.find(*[ApplicationId])).thenReturn(successful(None))
    }
  }

  object UpliftLinksRepositoryMock extends BaseUpliftLinksRepositoryRepoMock {
    val aMock = mock[UpliftLinksRepository]
  }
}
