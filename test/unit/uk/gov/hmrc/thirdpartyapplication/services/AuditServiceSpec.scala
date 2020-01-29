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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.mockito.ArgumentMatcher
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.{AuditHelper, AuditService}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.time.DateTimeUtils

class AuditServiceSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  class Setup {
    val mockAuditConnector = mock[AuditConnector]
    val auditService = new AuditService(mockAuditConnector)
  }

  def isSameDataEvent(expected: DataEvent) =
    new ArgumentMatcher[DataEvent] {
      override def matches(de: DataEvent): Boolean =
        de.auditSource == expected.auditSource &&
          de.auditType == expected.auditType &&
          de.tags == expected.tags &&
          de.detail == expected.detail
    }

  "AuditService audit" should {
    "pass through data to underlying auditConnector" in new Setup {
      val data = Map("some-header" -> "la-di-dah")
      implicit val hc: HeaderCarrier = HeaderCarrier()

      val event = DataEvent(
        auditSource = "third-party-application",
        auditType = AppCreated.auditType,
        tags = hc.toAuditTags(AppCreated.name, "-"),
        detail = hc.toAuditDetails(data.toSeq: _*)
      )

      auditService.audit(AppCreated, data)
      verify(auditService.auditConnector).sendEvent(argThat(isSameDataEvent(event)))(*, *)
    }

    "add user context where it exists in the header carrier" in new Setup {
      val data = Map("some-header" -> "la-di-dah")
      val email = "test@example.com"
      val name = "John Smith"
      implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
        LOGGED_IN_USER_EMAIL_HEADER -> email,
        LOGGED_IN_USER_NAME_HEADER -> name
      )

      val event = DataEvent(
        auditSource = "third-party-application",
        auditType = AppCreated.auditType,
        tags = hc.toAuditTags(AppCreated.name, "-"),
        detail = hc.toAuditDetails(data.toSeq: _*)
      )

      val expected = event.copy(
        tags = event.tags ++ Map(
          "developerEmail" -> email,
          "developerFullName" -> name
        )
      )

      auditService.audit(AppCreated, data)
      verify(auditService.auditConnector).sendEvent(argThat(isSameDataEvent(expected)))(*, *)
    }

    "add as much user context as possible where only partial data exists" in new Setup {
      val data = Map("some-header" -> "la-di-dah")
      val email = "test@example.com"
      val name = "John Smith"
      implicit val emailHc: HeaderCarrier = HeaderCarrier().withExtraHeaders(LOGGED_IN_USER_EMAIL_HEADER -> email)

      val event = DataEvent(
        auditSource = "third-party-application",
        auditType = AppCreated.auditType,
        tags = emailHc.toAuditTags(AppCreated.name, "-"),
        detail = emailHc.toAuditDetails(data.toSeq: _*)
      )

      val expected = event.copy(
        tags = event.tags ++ Map(
          "developerEmail" -> email
        )
      )

      auditService.audit(AppCreated, data)
      verify(auditService.auditConnector).sendEvent(argThat(isSameDataEvent(expected)))(*, *)
    }
  }

  "AuditHelper calculateAppChanges" should {

    val id = UUID.randomUUID()
    val admin = Collaborator("test@example.com", ADMINISTRATOR)
    val tokens = ApplicationTokens(
      EnvironmentToken("prodId", "prodSecret", "prodToken")
    )
    val previousApp = ApplicationData(
      id = id,
      name = "app name",
      normalisedName = "app name",
      collaborators = Set(admin),
      wso2Password = "wso2Password",
      wso2ApplicationName = "wso2ApplicationName",
      wso2Username = "wso2Username",
      tokens = tokens,
      state = testingState(),
      createdOn = DateTimeUtils.now,
      lastAccess = Some(DateTimeUtils.now)
    )

    val updatedApp = previousApp.copy(
      name = "new name",
      access = Standard(
        Seq("http://new-url.example.com", "http://new-url.example.com/other-redirect"),
        Some("http://new-url.example.com/terms-and-conditions"),
        Some("http://new-url.example.com/privacy-policy")
      )
    )

    val commonAuditData = Map(
      "applicationId" -> id.toString
    )

    val appNameAudit =
      AppNameChanged ->
        (Map("newApplicationName" -> "new name") ++ commonAuditData)

    val appPrivacyAudit =
      AppPrivacyPolicyUrlChanged ->
        (Map("newPrivacyPolicyUrl" -> "http://new-url.example.com/privacy-policy") ++ commonAuditData)

    val appRedirectUrisAudit =
      AppRedirectUrisChanged ->
        (Map("newRedirectUris" -> "http://new-url.example.com,http://new-url.example.com/other-redirect") ++ commonAuditData)

    val appTermsAndConditionsUrlAudit =
      AppTermsAndConditionsUrlChanged ->
        (Map("newTermsAndConditionsUrl" -> "http://new-url.example.com/terms-and-conditions") ++ commonAuditData)

    "produce the audit events required by an application update" in {
      AuditHelper.calculateAppChanges(previousApp, updatedApp) shouldEqual
        Set(appNameAudit, appPrivacyAudit, appRedirectUrisAudit, appTermsAndConditionsUrlAudit)
    }

    "only produce audit events if the fields have been updated" in {
      val partiallyUpdatedApp = previousApp.copy(
        name = updatedApp.name
      )

      AuditHelper.calculateAppChanges(previousApp, partiallyUpdatedApp) shouldEqual Set(appNameAudit)
    }
  }
}
