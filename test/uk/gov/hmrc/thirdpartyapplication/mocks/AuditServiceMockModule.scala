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

import cats.data.NonEmptyList
import org.mockito.captor.{ArgCaptor, Captor}
import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.{AuditAction, AuditService}

trait AuditServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseAuditServiceMock {
    def aMock: AuditService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: org.mockito.verification.VerificationMode) = MockitoSugar.verify(aMock, mode)

    object Audit {

      def thenReturnSuccessWhen(action: AuditAction, data: Map[String, String]) =
        when(aMock.audit(eqTo(action), eqTo(data))(using *)).thenReturn(successful(AuditResult.Success))

      def thenReturnSuccess() = {
        when(aMock.audit(*, *)(using *)).thenReturn(successful(AuditResult.Success))
      }

      def verifyNeverCalled() =
        AuditServiceMock.verify(never).audit(*, *)(using *)

      def verifyCalled() =
        AuditServiceMock.verify.audit(*, *)(using *)

      def verifyCalledWith(auditAction: AuditAction, data: Map[String, String], hc: HeaderCarrier) =
        AuditServiceMock.verify.audit(refEq(auditAction), eqTo(data))(using eqTo(hc))

      def verify(verificationMode: VerificationMode)(auditAction: AuditAction, data: Map[String, String], hc: HeaderCarrier) =
        AuditServiceMock.verify(verificationMode).audit(refEq(auditAction), eqTo(data))(using eqTo(hc))

      def verifyData(auditAction: AuditAction) = {
        val capture: Captor[Map[String, String]] = ArgCaptor[Map[String, String]]
        AuditServiceMock.verify.audit(refEq(auditAction), capture)(using *)
        capture.value
      }
    }

    object AuditWithTags {

      def thenReturnSuccess() = {
        when(aMock.audit(*, *, *)(using *)).thenReturn(successful(AuditResult.Success))
      }
    }

    object AuditGatekeeperAction {

      def thenReturnSuccess() =
        when(aMock.auditGatekeeperAction(*, *, *, *)(using *)).thenReturn(successful(AuditResult.Success))

      def verifyUserName() = {
        val captureGatekeeperUser: Captor[String] = ArgCaptor[String]
        verify.auditGatekeeperAction(captureGatekeeperUser, *, *, *)(using *)
        captureGatekeeperUser.value
      }

      def verifyAction() = {
        val captureAction: Captor[AuditAction] = ArgCaptor[AuditAction]

        verify.auditGatekeeperAction(*, *, captureAction, *)(using *)
        captureAction.value
      }

      def verifyExtras() = {
        val captureExtras: Captor[Map[String, String]] = ArgCaptor[Map[String, String]]
        verify.auditGatekeeperAction(*, *, *, captureExtras)(using *)
        captureExtras.value
      }
    }

    object ApplyEvents {

      def succeeds() = {
        when(aMock.applyEvents(*, *)(using *)).thenReturn(successful(None))
      }

      def verifyCalledWith(app: StoredApplication, events: NonEmptyList[ApplicationEvent]) = {
        verify.applyEvents(eqTo(app), eqTo(events))(using *)
      }
    }
  }

  object AuditServiceMock extends BaseAuditServiceMock {
    val aMock = mock[AuditService]
  }
}
