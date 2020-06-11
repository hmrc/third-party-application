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

package uk.gov.hmrc.thirdpartyapplication.mocks

import org.mockito.captor.{ArgCaptor, Captor}
import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.services.{AuditAction, AuditService}

import scala.concurrent.Future.successful

trait AuditServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  object AuditServiceMock {
    lazy val aMock =  mock[AuditService]

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: org.mockito.verification.VerificationMode) = MockitoSugar.verify(aMock,mode)

    object Audit {
      def thenReturnSuccessWhen(action: AuditAction, data: Map[String, String]) =
        when(aMock.audit(eqTo(action),eqTo(data))(*)).thenReturn(successful(AuditResult.Success))

      def thenReturnSuccess() = {
        when(aMock.audit(*,*)(*)).thenReturn(successful(AuditResult.Success))
      }

      def verifyNeverCalled() =
        AuditServiceMock.verify(never).audit(*,*)(*)

      def verifyCalled() =
        AuditServiceMock.verify.audit(*, *)(*)

      def verifyCalledWith(auditAction: AuditAction, data: Map[String,String], hc: HeaderCarrier) =
        AuditServiceMock.verify.audit(refEq(auditAction), eqTo(data))(eqTo(hc))

      def verify(verificationMode: VerificationMode)(auditAction: AuditAction, data: Map[String,String], hc: HeaderCarrier) =
        AuditServiceMock.verify(verificationMode).audit(refEq(auditAction), eqTo(data))(eqTo(hc))

      def verifyData(auditAction: AuditAction) = {
        val capture: Captor[Map[String, String]] = ArgCaptor[Map[String, String]]
        AuditServiceMock.verify.audit(refEq(auditAction), capture)(*)
        capture.value
      }
    }

    object AuditWithTags {
      def thenReturnSuccess() = {
        when(aMock.audit(*,*,*)(*)).thenReturn(successful(AuditResult.Success))
      }
    }
  }
}
