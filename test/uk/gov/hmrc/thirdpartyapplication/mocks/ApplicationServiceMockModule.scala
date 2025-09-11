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

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

import cats.data.OptionT
import cats.implicits.catsStdInstancesForFuture
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaborators
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.CreateApplicationRequest
import uk.gov.hmrc.thirdpartyapplication.controllers.DeleteApplicationRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models.Deleted
import uk.gov.hmrc.thirdpartyapplication.models.CreateApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.util._

trait ApplicationServiceMockModule extends MockitoSugar with ArgumentMatchersSugar with StoredApplicationFixtures {

  protected trait BaseApplicationServiceMock {

    def aMock: ApplicationService

    object Fetch {

      def thenReturn(applicationData: StoredApplication) = {
        val r: OptionT[Future, ApplicationWithCollaborators] = OptionT.pure[Future](applicationData.asAppWithCollaborators)
        when(aMock.fetch(*[ApplicationId])).thenReturn(r)
      }

      def thenReturn(response: ApplicationWithCollaborators) = {
        when(aMock.fetch(*[ApplicationId])).thenReturn(OptionT.pure[Future](response))
      }

      def thenReturnFor(id: ApplicationId)(response: ApplicationWithCollaborators) = {
        when(aMock.fetch(eqTo(id))).thenReturn(OptionT.pure[Future](response))
      }

      def thenReturnNothing() = when(aMock.fetch(*[ApplicationId])).thenReturn(OptionT.fromOption[Future](None))

      def thenReturnNothingFor(id: ApplicationId) = when(aMock.fetch(id)).thenReturn(OptionT.fromOption[Future](None))

      def thenThrow(ex: Exception) = when(aMock.fetch(*[ApplicationId])).thenReturn(OptionT.liftF(failed(ex)))

      def thenThrowFor(id: ApplicationId)(ex: Exception) = when(aMock.fetch(id)).thenReturn(OptionT.liftF(failed(ex)))
    }

    object Create {

      def onRequestReturn(request: CreateApplicationRequest)(response: CreateApplicationResponse) = {
        when(aMock.create(eqTo(request))(*)).thenReturn(successful(response))
      }
    }

    object DeleteApplication {
      def thenSucceeds() = when(aMock.deleteApplication(*[ApplicationId], *, *)(*)).thenReturn(successful(Deleted))

      def verifyCalledWith(applicationId: ApplicationId, request: Option[DeleteApplicationRequest]) = {
        verify(aMock).deleteApplication(eqTo(applicationId), eqTo(request), *)(*)
      }
    }

    object GetAppsForAdminOrRI {

      def onRequestReturn(request: LaxEmailAddress)(response: List[ApplicationWithCollaborators]) = {
        when(aMock.getAppsForResponsibleIndividualOrAdmin(eqTo(request))).thenReturn(successful(response))
      }

      def thenReturnNothingFor(request: LaxEmailAddress) = {
        when(aMock.getAppsForResponsibleIndividualOrAdmin(eqTo(request))).thenReturn(successful(List.empty))
      }

      def thenThrowFor(request: LaxEmailAddress)(exception: RuntimeException) = {
        when(aMock.getAppsForResponsibleIndividualOrAdmin(eqTo(request))).thenReturn(failed(exception))
      }

      def verifyCalledWith(request: LaxEmailAddress) = {
        verify(aMock).getAppsForResponsibleIndividualOrAdmin(eqTo(request))
      }
    }
  }

  object ApplicationServiceMock extends BaseApplicationServiceMock {
    val aMock = mock[ApplicationService]
  }
}
