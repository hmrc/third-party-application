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

import scala.concurrent.Future.{failed, successful}

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationWithCollaborators, ApplicationWithSubscriptions, PaginatedApplications}
import uk.gov.hmrc.thirdpartyapplication.controllers.query.ApplicationQuery.GeneralOpenEndedApplicationQuery
import uk.gov.hmrc.thirdpartyapplication.controllers.query.{ApplicationQuery, SingleApplicationQuery}
import uk.gov.hmrc.thirdpartyapplication.services.query.QueryService
import uk.gov.hmrc.thirdpartyapplication.util._

trait QueryServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with StoredApplicationFixtures {

  protected trait BaseQueryServiceMock {
    def aMock: QueryService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object FetchSingleApplicationByQuery {

      def thenReturnsFor(qry: SingleApplicationQuery, app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(Left(Some(app))))

      def thenReturns(app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplicationByQuery(*)).thenReturn(successful(Left(Some(app))))

      def thenReturnsFor(qry: SingleApplicationQuery, app: ApplicationWithSubscriptions) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(Right(Some(app))))

      def thenReturns(app: ApplicationWithSubscriptions) =
        when(aMock.fetchSingleApplicationByQuery(*)).thenReturn(successful(Right(Some(app))))

      def thenReturnsLeftNoneFor(qry: SingleApplicationQuery) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(Left(None)))

      def thenReturnsRightNoneFor(qry: SingleApplicationQuery) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(Right(None)))
    }

    object FetchSingleApplication {

      def thenReturns(app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplication(*)).thenReturn(successful(Some(app)))

      def thenReturnsNothing() =
        when(aMock.fetchSingleApplication(*)).thenReturn(successful(None))

      def thenReturnsFor(qry: SingleApplicationQuery, app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplication(eqTo(qry))).thenReturn(successful(Some(app)))

      def thenReturnsNothingFor(qry: SingleApplicationQuery) =
        when(aMock.fetchSingleApplication(*)).thenReturn(successful(None))

      def thenFails(exc: Exception) =
        when(aMock.fetchSingleApplication(*)).thenReturn(failed(exc))
    }

    object FetchApplicationsByQuery {

      def thenReturnsAppsWithCollaboratorsFor(qry: GeneralOpenEndedApplicationQuery, apps: ApplicationWithCollaborators*) =
        when(aMock.fetchApplicationsByQuery(eqTo(qry))).thenReturn(successful(Left(apps.toList)))

      def thenReturnsNoAppsWithCollaborators() =
        when(aMock.fetchApplicationsByQuery(*)).thenReturn(successful(Left(List.empty)))

      def thenReturnsAppsWithSubscriptionsFor(qry: GeneralOpenEndedApplicationQuery, apps: ApplicationWithSubscriptions*) =
        when(aMock.fetchApplicationsByQuery(eqTo(qry))).thenReturn(successful(Right(apps.toList)))

      def thenReturnsNoAppsWithSubscriptions() =
        when(aMock.fetchApplicationsByQuery(*)).thenReturn(successful(Right(List.empty)))

      def thenFails(exc: Exception) =
        when(aMock.fetchApplicationsByQuery(*)).thenReturn(failed(exc))

      def thenFailsFor(qry: ApplicationQuery.GeneralOpenEndedApplicationQuery, exc: Exception) =
        when(aMock.fetchApplicationsByQuery(eqTo(qry))).thenReturn(failed(exc))
    }

    object FetchPaginatedApplications {

      def thenReturnsFor(qry: ApplicationQuery.PaginatedApplicationQuery, pas: PaginatedApplications) =
        when(aMock.fetchPaginatedApplications(eqTo(qry))).thenReturn(successful(pas))

      def thenReturnsNoApps(count: Int) =
        when(aMock.fetchPaginatedApplications(*)).thenReturn(successful(PaginatedApplications(List.empty, 1, 25, count, 0)))
    }

    object FetchApplications {
      def verifyCalledWith(qry: GeneralOpenEndedApplicationQuery) = QueryServiceMock.verify.fetchApplications(eqTo(qry))

      def verifyNeverCalled() = QueryServiceMock.verify(never).fetchApplications(*)

      def thenReturns(apps: ApplicationWithCollaborators*) =
        when(aMock.fetchApplications(*)).thenReturn(successful(apps.toList))

      def thenReturnsNothing() =
        when(aMock.fetchApplications(*)).thenReturn(successful(List.empty))

      def thenReturnsFor(qry: GeneralOpenEndedApplicationQuery, apps: ApplicationWithCollaborators*) =
        when(aMock.fetchApplications(eqTo(qry))).thenReturn(successful(apps.toList))

      def thenReturnsNothingFor(qry: GeneralOpenEndedApplicationQuery) =
        when(aMock.fetchApplications(eqTo(qry))).thenReturn(successful(List.empty))

      def thenReturnsFailure(exc: Exception) =
        when(aMock.fetchApplications(*)).thenReturn(failed(exc))
    }

    object FetchApplicationsWithSubscriptions {
      def verifyCalledWith(qry: GeneralOpenEndedApplicationQuery) = QueryServiceMock.verify.fetchApplicationsWithSubscriptions(eqTo(qry))

      def verifyNeverCalled() = QueryServiceMock.verify(never).fetchApplicationsWithSubscriptions(*)

      def thenReturns(apps: ApplicationWithSubscriptions*) =
        when(aMock.fetchApplicationsWithSubscriptions(*)).thenReturn(successful(apps.toList))

      def thenReturnsNothing() =
        when(aMock.fetchApplicationsWithSubscriptions(*)).thenReturn(successful(List.empty))

      def thenReturnsFor(qry: GeneralOpenEndedApplicationQuery, apps: ApplicationWithSubscriptions*) =
        when(aMock.fetchApplicationsWithSubscriptions(eqTo(qry))).thenReturn(successful(apps.toList))

      def thenReturnsFailure(exc: Exception) =
        when(aMock.fetchApplicationsWithSubscriptions(*)).thenReturn(failed(exc))

      def thenReturnsNothingFor(qry: GeneralOpenEndedApplicationQuery) =
        when(aMock.fetchApplicationsWithSubscriptions(eqTo(qry))).thenReturn(successful(List.empty))
    }

  }

  object QueryServiceMock extends BaseQueryServiceMock {
    val aMock = mock[QueryService]
  }
}
