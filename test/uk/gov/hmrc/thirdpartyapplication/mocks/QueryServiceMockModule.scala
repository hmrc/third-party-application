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
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.QueriedApplication
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery.GeneralOpenEndedApplicationQuery
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.{ApplicationQuery, SingleApplicationQuery}
import uk.gov.hmrc.thirdpartyapplication.services.query.QueryService
import uk.gov.hmrc.thirdpartyapplication.util._

trait QueryServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with StoredApplicationFixtures {

  protected trait BaseQueryServiceMock {
    def aMock: QueryService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object FetchSingleApplicationByQuery {

      def thenReturnsFor(qry: SingleApplicationQuery, app: QueriedApplication) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(Some(app)))

      def thenReturnsFor(qry: SingleApplicationQuery, app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(Some(QueriedApplication(app))))

      def thenReturns(app: ApplicationWithCollaborators) =
        when(aMock.fetchSingleApplicationByQuery(*)).thenReturn(successful(Some(QueriedApplication(app))))

      def thenReturnsFor(qry: SingleApplicationQuery, app: ApplicationWithSubscriptions) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(Some(QueriedApplication(app))))

      def thenReturns(app: ApplicationWithSubscriptions) =
        when(aMock.fetchSingleApplicationByQuery(*)).thenReturn(successful(Some(QueriedApplication(app))))

      def thenReturnsNone() =
        when(aMock.fetchSingleApplicationByQuery(*)).thenReturn(successful(None))

      def thenReturnsNoneFor(qry: SingleApplicationQuery) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(successful(None))

      def thenFails(exc: Exception) =
        when(aMock.fetchSingleApplicationByQuery(*)).thenReturn(failed(exc))

      def thenFailsFor(qry: SingleApplicationQuery, exc: Exception) =
        when(aMock.fetchSingleApplicationByQuery(eqTo(qry))).thenReturn(failed(exc))
    }

    object FetchApplicationsByQuery {

      def thenReturns(apps: ApplicationWithCollaborators*) =
        when(aMock.fetchApplicationsByQuery(*)).thenReturn(successful(apps.toList.map(QueriedApplication.apply)))

      def thenReturnsFor(qry: GeneralOpenEndedApplicationQuery, apps: ApplicationWithCollaborators*) =
        when(aMock.fetchApplicationsByQuery(eqTo(qry))).thenReturn(successful(apps.toList.map(QueriedApplication.apply)))

      def thenReturnsNoAppsFor(qry: GeneralOpenEndedApplicationQuery) =
        when(aMock.fetchApplicationsByQuery(eqTo(qry))).thenReturn(successful(List.empty))

      def thenReturnsNoApps() =
        when(aMock.fetchApplicationsByQuery(*)).thenReturn(successful(List.empty))

      def thenReturnsSubsFor(qry: GeneralOpenEndedApplicationQuery, apps: ApplicationWithSubscriptions*) =
        when(aMock.fetchApplicationsByQuery(eqTo(qry))).thenReturn(successful(apps.toList.map(QueriedApplication.apply)))

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
  }

  object QueryServiceMock extends BaseQueryServiceMock {
    val aMock = mock[QueryService]
  }
}
