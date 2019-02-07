/*
 * Copyright 2019 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.helpers

import org.mockito.Matchers.any
import org.mockito.Mockito.when
import uk.gov.hmrc.auth.core.SessionRecordNotFound
import uk.gov.hmrc.auth.core.retrieve.{EmptyRetrieval, Retrieval}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.controllers.AuthorisationWrapper

import scala.concurrent.{ExecutionContext, Future}

object AuthSpecHelpers {
  def givenUserIsAuthenticated(underTest: AuthorisationWrapper) = {
    when(underTest.authConnector.authorise(any, any[Retrieval[Any]])(any[HeaderCarrier], any[ExecutionContext])).thenReturn(Future.successful())
  }

  def givenUserIsNotAuthenticated(underTest: AuthorisationWrapper) = {
    when(underTest.authConnector.authorise(any, any[Retrieval[Any]])(any[HeaderCarrier], any[ExecutionContext]))
      .thenReturn(Future.failed(new SessionRecordNotFound))
  }
}
