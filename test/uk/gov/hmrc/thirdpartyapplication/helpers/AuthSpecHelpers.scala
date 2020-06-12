/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.helpers

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.thirdpartyapplication.controllers.AuthorisationWrapper

import scala.concurrent.Future

object AuthSpecHelpers extends MockitoSugar with ArgumentMatchersSugar {
  def givenUserIsAuthenticated(underTest: AuthorisationWrapper) = {
    when(underTest.authConnector.authorise[Unit](*, *)(*, *)).thenReturn(Future.successful(()))
  }

  def givenUserIsNotAuthenticated(underTest: AuthorisationWrapper) = {
    when(underTest.authConnector.authorise[Unit](*, *)(*, *)).thenReturn(Future.failed(new SessionRecordNotFound))
  }
}
