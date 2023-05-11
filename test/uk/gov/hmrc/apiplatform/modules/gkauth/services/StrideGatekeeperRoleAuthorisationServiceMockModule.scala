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

package uk.gov.hmrc.apiplatform.modules.gkauth.services

import scala.concurrent.Future.successful

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import play.api.mvc.Results._

trait StrideGatekeeperRoleAuthorisationServiceMockModule {
  self: MockitoSugar with ArgumentMatchersSugar =>

  protected trait BaseStrideGatekeeperRoleAuthorisationServiceMock {
    def aMock: StrideGatekeeperRoleAuthorisationService

    object EnsureHasGatekeeperRole {
      def authorised = when(aMock.ensureHasGatekeeperRole()(*)).thenReturn(successful(None))

      def notAuthorised = when(aMock.ensureHasGatekeeperRole()(*)).thenReturn(successful(Some(Unauthorized("bang"))))
    }
  }

  object StrideGatekeeperRoleAuthorisationServiceMock extends BaseStrideGatekeeperRoleAuthorisationServiceMock {
    val aMock = mock[StrideGatekeeperRoleAuthorisationService]
  }
}
