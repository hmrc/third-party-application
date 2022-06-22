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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.mocks

import org.mockito.MockitoSugar
import org.mockito.ArgumentMatchersSugar
import scala.concurrent.Future.successful
import uk.gov.hmrc.thirdpartyapplication.util.ApplicationTestData
import uk.gov.hmrc.apiplatform.modules.upliftlinks.service.UpliftLinkService
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import cats.data.OptionT
import scala.concurrent.Future
import cats.implicits._
import scala.concurrent.ExecutionContext.Implicits.global

trait UpliftLinkServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with ApplicationTestData {

  protected trait BaseUpliftLinksServiceMock {
    def aMock: UpliftLinkService

    object CreateUpliftLink {

      def thenReturn(sandboxApplicationId: ApplicationId, productionApplicationId: ApplicationId) =
        when(aMock.createUpliftLink(*[ApplicationId], *[ApplicationId])).thenReturn(successful(UpliftLink(sandboxApplicationId, productionApplicationId)))
    }

    object GetSandboxAppForProductionAppId {
      def thenReturn(appId: ApplicationId) = when(aMock.getSandboxAppForProductionAppId(*[ApplicationId])).thenReturn(OptionT.pure[Future](appId))
      def thenReturnNothing                = when(aMock.getSandboxAppForProductionAppId(*[ApplicationId])).thenReturn(OptionT.fromOption(None))
    }
  }

  object UpliftLinkServiceMock extends BaseUpliftLinksServiceMock {
    val aMock = mock[UpliftLinkService]
  }
}
