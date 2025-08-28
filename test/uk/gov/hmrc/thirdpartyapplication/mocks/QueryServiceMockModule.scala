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

package uk.gov.hmrc.thirdpartyapplication.mocks

import scala.concurrent.Future.successful

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaborators, ApplicationWithSubscriptions, PaginatedApplications}
import uk.gov.hmrc.thirdpartyapplication.controllers.query.ApplicationQuery.GeneralOpenEndedApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.controllers.query.SingleApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.services.QueryService
import uk.gov.hmrc.thirdpartyapplication.util._

trait QueryServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with StoredApplicationFixtures {

  protected trait BaseQueryServiceMock {
    def aMock: QueryService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object FetchSingleApplication {

      def thenReturns(app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplication(*)).thenReturn(successful(Left(Some(app))))

      def thenReturns(app: ApplicationWithSubscriptions) =
        when(aMock.fetchSingleApplication(*)).thenReturn(successful(Right(Some(app))))

      def thenReturnsNothing() =
        when(aMock.fetchSingleApplication(*)).thenReturn(successful(Left(None)))
    }

    object FetchSingleApplicationWithCollaborators {

      def thenReturns(app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplicationWithCollaborators(*)).thenReturn(successful(Some(app)))

      def thenReturnsNothing() =
        when(aMock.fetchSingleApplicationWithCollaborators(*)).thenReturn(successful(None))

      def thenReturnsFor(qry: SingleApplicationQuery, app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplicationWithCollaborators(eqTo(qry))).thenReturn(successful(Some(app)))

      def thenReturnsNothingFor(qry: SingleApplicationQuery) =
        when(aMock.fetchSingleApplicationWithCollaborators(*)).thenReturn(successful(None))
    }

    object FetchApplications {

      def thenReturnsAppsWithCollaborators(apps: ApplicationWithCollaborators*) =
        when(aMock.fetchApplications(*)).thenReturn(successful(Left(apps.toList)))

      def thenReturnsNoAppsWithCollaborators() =
        when(aMock.fetchApplications(*)).thenReturn(successful(Left(List.empty)))

      def thenReturnsAppsWithSubscriptions(apps: ApplicationWithSubscriptions*) =
        when(aMock.fetchApplications(*)).thenReturn(successful(Right(apps.toList)))

      def thenReturnsNoAppsWithSubscriptions() =
        when(aMock.fetchApplications(*)).thenReturn(successful(Right(List.empty)))
    }

    object FetchPaginatedApplications {

      def thenReturns(pas: PaginatedApplications) =
        when(aMock.fetchPaginatedApplications(*)).thenReturn(successful(pas))

      def thenReturnsNoApps(count: Int) =
        when(aMock.fetchPaginatedApplications(*)).thenReturn(successful(PaginatedApplications(List.empty, 1, 25, count, 0)))
    }

    object FetchApplicationsWithCollaborators {
      def verifyCalledWith(qry: GeneralOpenEndedApplicationQuery) = QueryServiceMock.verify.fetchApplicationsWithCollaborators(eqTo(qry))

      def verifyNeverCalled() = QueryServiceMock.verify(never).fetchApplicationsWithCollaborators(*)

      def thenReturns(apps: ApplicationWithCollaborators*) =
        when(aMock.fetchApplicationsWithCollaborators(*)).thenReturn(successful(apps.toList))

      def thenReturnsNothing() =
        when(aMock.fetchApplicationsWithCollaborators(*)).thenReturn(successful(List.empty))

      def thenReturnsNothingWhen(qry: GeneralOpenEndedApplicationQuery) =
        when(aMock.fetchApplicationsWithCollaborators(eqTo(qry))).thenReturn(successful(List.empty))
    }

  }

  object QueryServiceMock extends BaseQueryServiceMock {
    val aMock = mock[QueryService]
  }
}
