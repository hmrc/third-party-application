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

package uk.gov.hmrc.thirdpartyapplication.services

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import cats.data.NonEmptyList
import org.mockito.ArgumentMatcher

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{AuditConnector, AuditResult}
import uk.gov.hmrc.play.audit.model.DataEvent

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvents, EventId}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.{MarkAnswer, QuestionsAndAnswersToMap}
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class AuditServiceSpec extends AsyncHmrcSpec with ApplicationStateUtil
    with ApplicationTestData with SubmissionsTestData with SubmissionsServiceMockModule {

  class Setup {
    val mockAuditConnector = mock[AuditConnector]
    val auditService       = new AuditService(mockAuditConnector, SubmissionsServiceMock.aMock, clock)
  }

  val timestamp             = now
  val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

  val testImportantSubmissionData = ImportantSubmissionData(
    Some("organisationUrl.com"),
    responsibleIndividual,
    Set(ServerLocation.InUK),
    TermsAndConditionsLocations.InDesktopSoftware,
    PrivacyPolicyLocations.InDesktopSoftware,
    List.empty
  )

  val applicationData: ApplicationData = anApplicationData(
    applicationId,
    access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData))
  )
  val instigator                       = applicationData.collaborators.head.userId

  def isSameDataEvent(expected: DataEvent) =
    new ArgumentMatcher[DataEvent] {

      override def matches(de: DataEvent): Boolean =
        de.auditSource == expected.auditSource &&
          de.auditType == expected.auditType &&
          de.tags == expected.tags &&
          de.detail == expected.detail
    };

  "AuditService audit" should {
    "pass through data to underlying auditConnector" in new Setup {
      val data                       = Map("some-header" -> "la-di-dah")
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
      val data                       = Map("some-header" -> "la-di-dah")
      val email                      = "test@example.com"
      val name                       = "John Smith"
      implicit val hc: HeaderCarrier = HeaderCarrier().withExtraHeaders(
        LOGGED_IN_USER_EMAIL_HEADER -> email,
        LOGGED_IN_USER_NAME_HEADER  -> name
      )

      val event = DataEvent(
        auditSource = "third-party-application",
        auditType = AppCreated.auditType,
        tags = hc.toAuditTags(AppCreated.name, "-"),
        detail = hc.toAuditDetails(data.toSeq: _*)
      )

      val expected = event.copy(
        tags = event.tags ++ Map(
          "developerEmail"    -> email,
          "developerFullName" -> name
        )
      )

      auditService.audit(AppCreated, data)
      verify(auditService.auditConnector).sendEvent(argThat(isSameDataEvent(expected)))(*, *)
    }

    "add as much user context as possible where only partial data exists" in new Setup {
      val data                            = Map("some-header" -> "la-di-dah")
      val email                           = "test@example.com"
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

  "applyEvents" should {
    val gatekeeperUser = "Bob.TheBuilder"
    val reasons        = "Reasons description text"
    val requesterEmail = "bill.badger@rupert.com"
    val requesterName  = "bill badger"
    val appInTesting   = applicationData.copy(state = ApplicationStateExamples.testing)

    val collaboratorActor          = Actors.AppCollaborator(applicationData.collaborators.head.emailAddress)
    implicit val hc: HeaderCarrier = HeaderCarrier()

    "applyEvents with a single ApplicationApprovalRequestDeclined event" in new Setup {

      val appApprovalRequestDeclined = ApplicationEvents.ApplicationApprovalRequestDeclined(
        EventId.random,
        applicationId,
        instant,
        Actors.GatekeeperUser(gatekeeperUser),
        gatekeeperUser,
        gatekeeperUser.toLaxEmail,
        SubmissionId.random,
        1,
        reasons,
        requesterName,
        requesterEmail.toLaxEmail
      )

      val tags                       = Map("gatekeeperId" -> gatekeeperUser)
      val questionsWithAnswers       = QuestionsAndAnswersToMap(declinedSubmission)
      val declinedData               = Map("status" -> "declined", "reasons" -> reasons)
      val fmt                        = DateTimeFormatter.ISO_DATE_TIME
      val submissionPreviousInstance = declinedSubmission.instances.tail.head
      val submittedOn: LocalDateTime = submissionPreviousInstance.statusHistory.find(s => s.isSubmitted).map(_.timestamp).get
      val declinedOn: LocalDateTime  = submissionPreviousInstance.statusHistory.find(s => s.isDeclined).map(_.timestamp).get
      val dates                      = Map(
        "submission.started.date"   -> declinedSubmission.startedOn.format(fmt),
        "submission.submitted.date" -> submittedOn.format(fmt),
        "submission.declined.date"  -> declinedOn.format(fmt)
      )
      val markedAnswers              = MarkAnswer.markSubmission(declinedSubmission)
      val nbrOfFails                 = markedAnswers.filter(_._2 == Fail).size
      val nbrOfWarnings              = markedAnswers.filter(_._2 == Warn).size
      val counters                   = Map(
        "submission.failures" -> nbrOfFails.toString,
        "submission.warnings" -> nbrOfWarnings.toString
      )
      val gatekeeperDetails          = Map(
        "applicationId"          -> appInTesting.id.value.toString,
        "applicationName"        -> appInTesting.name,
        "upliftRequestedByEmail" -> appInTesting.state.requestedByEmailAddress.getOrElse("-"),
        "applicationAdmins"      -> appInTesting.admins.map(_.emailAddress).mkString(", ")
      )

      val extraDetail       = questionsWithAnswers ++ declinedData ++ dates ++ counters ++ gatekeeperDetails
      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = ApplicationApprovalDeclined.auditType,
        tags = hc.toAuditTags(ApplicationApprovalDeclined.name, "-") ++ tags,
        detail = extraDetail
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))
      SubmissionsServiceMock.FetchLatest.thenReturn(declinedSubmission)

      val result = await(auditService.applyEvents(appInTesting, NonEmptyList.one(appApprovalRequestDeclined)))

      result shouldBe Some(AuditResult.Success)
      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a ClientSecretAdded event" in new Setup {

      val clientSecretAdded = ApplicationEvents.ClientSecretAddedV2(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        "name",
        "hashedSecret"
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = ClientSecretAddedAudit.auditType,
        tags = hc.toAuditTags(ClientSecretAddedAudit.name, "-"),
        detail = Map(
          "applicationId"    -> applicationId.value.toString,
          "newClientSecret"  -> clientSecretAdded.clientSecretName,
          "clientSecretType" -> "PRODUCTION"
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(clientSecretAdded)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a ClientSecretRemoved event" in new Setup {

      val clientSecretRemoved = ApplicationEvents.ClientSecretRemovedV2(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        "client secret ID",
        "secret name"
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = ClientSecretRemovedAudit.auditType,
        tags = hc.toAuditTags(ClientSecretRemovedAudit.name, "-"),
        detail = Map(
          "applicationId"       -> applicationId.value.toString,
          "removedClientSecret" -> clientSecretRemoved.clientSecretId
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(clientSecretRemoved)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a CollaboratorAdded event" in new Setup {

      val newCollaborator = "emailaddress".developer()

      val collaboratorAdded = ApplicationEvents.CollaboratorAddedV2(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        newCollaborator
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = CollaboratorAddedAudit.auditType,
        tags = hc.toAuditTags(CollaboratorAddedAudit.name, "-"),
        detail = Map(
          "applicationId"        -> applicationId.value.toString,
          "newCollaboratorEmail" -> newCollaborator.emailAddress.text,
          "newCollaboratorType"  -> newCollaborator.describeRole
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(collaboratorAdded)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a CollaboratorRemoved event" in new Setup {

      val removedCollaborator = applicationData.collaborators.head

      val collaboratorRemoved = ApplicationEvents.CollaboratorRemovedV2(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        removedCollaborator
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = CollaboratorRemovedAudit.auditType,
        tags = hc.toAuditTags(CollaboratorRemovedAudit.name, "-"),
        detail = Map(
          "applicationId"            -> applicationId.value.toString,
          "removedCollaboratorEmail" -> removedCollaborator.emailAddress.text,
          "removedCollaboratorType"  -> removedCollaborator.describeRole
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(collaboratorRemoved)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a ApiSubscribed event" in new Setup {

      val apiSubscribed = ApplicationEvents.ApiSubscribedV2(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        "context".asContext,
        "version".asVersion
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = Subscribed.auditType,
        tags = hc.toAuditTags(Subscribed.name, "-"),
        detail = Map(
          "applicationId" -> applicationId.value.toString,
          "apiVersion"    -> apiSubscribed.version.value,
          "apiContext"    -> apiSubscribed.context.value
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(apiSubscribed)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a ApiUnsubscribed event" in new Setup {

      val apiUnsubscribed = ApplicationEvents.ApiUnsubscribedV2(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        "context".asContext,
        "version".asVersion
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = Unsubscribed.auditType,
        tags = hc.toAuditTags(Unsubscribed.name, "-"),
        detail = Map(
          "applicationId" -> applicationId.value.toString,
          "apiVersion"    -> apiUnsubscribed.version.value,
          "apiContext"    -> apiUnsubscribed.context.value
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(apiUnsubscribed)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a RedirectUrisUpdated event" in new Setup {

      val redirectUrisUpdated = ApplicationEvents.RedirectUrisUpdatedV2(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        oldRedirectUris = List.empty,
        newRedirectUris = List("https://new-url.example.com", "https://new-url.example.com/other-redirect")
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = AppRedirectUrisChanged.auditType,
        tags = hc.toAuditTags(AppRedirectUrisChanged.name, "-"),
        detail = Map(
          "applicationId"   -> applicationId.value.toString,
          "newRedirectUris" -> redirectUrisUpdated.newRedirectUris.mkString(",")
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(redirectUrisUpdated)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    // "applyEvents with a ApplicationDeletedByGatekeeper event" in new Setup {

    //   val event = ApplicationDeletedByGatekeeper(
    //     EventId.random,
    //     applicationData.id,
    //     timestamp,
    //     gatekeeperActor,
    //     ClientId.random,
    //     "wso2name",
    //     "a reason",
    //     requesterEmail
    //   )

    //   val expectedDataEvent = DataEvent(
    //     auditSource = "third-party-application",
    //     auditType = AuditAction.ApplicationDeleted.auditType,
    //     tags = hc.toAuditTags("gatekeeperId", gatekeeperActor.user),
    //     detail = Map(
    //       "applicationAdmins"       -> applicationData.admins.map(_.emailAddress).mkString(", "),
    //       "applicationId"           -> applicationData.id.value.toString,
    //       "applicationName"         -> applicationData.name,
    //       "upliftRequestedByEmail"  -> "john.smith@example.com",
    //       "requestedByEmailAddress" -> requesterEmail
    //     )
    //   )

    //   when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

    //   val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(event)))

    //   result shouldBe Some(AuditResult.Success)

    //   verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    // }

  }

  "AuditHelper calculateAppChanges" should {

    val id          = ApplicationId.random
    val admin       = "test@example.com".admin()
    val tokens      = ApplicationTokens(
      Token(ClientId("prodId"), "prodToken")
    )
    val previousApp = ApplicationData(
      id = id,
      name = "app name",
      normalisedName = "app name",
      collaborators = Set(admin),
      wso2ApplicationName = "wso2ApplicationName",
      tokens = tokens,
      state = testingState(),
      createdOn = now,
      lastAccess = Some(now)
    )

    val updatedApp = previousApp.copy(
      name = "new name",
      access = Access.Standard(
        List("http://new-url.example.com", "http://new-url.example.com/other-redirect"),
        Some("http://new-url.example.com/terms-and-conditions"),
        Some("http://new-url.example.com/privacy-policy")
      )
    )

    val commonAuditData = Map(
      "applicationId" -> id.value.toString
    )

    val appNameAudit =
      AppNameChanged ->
        (Map("newApplicationName" -> "new name") ++ commonAuditData)

    val appPrivacyAudit =
      AppPrivacyPolicyUrlChanged ->
        (Map("newPrivacyPolicyUrl" -> "http://new-url.example.com/privacy-policy") ++ commonAuditData)

    val appTermsAndConditionsUrlAudit =
      AppTermsAndConditionsUrlChanged ->
        (Map("newTermsAndConditionsUrl" -> "http://new-url.example.com/terms-and-conditions") ++ commonAuditData)

    "produce the audit events required by an application update" in {
      AuditHelper.calculateAppChanges(previousApp, updatedApp) shouldEqual
        Set(appNameAudit, appPrivacyAudit, appTermsAndConditionsUrlAudit)
    }

    "only produce audit events if the fields have been updated" in {
      val partiallyUpdatedApp = previousApp.copy(
        name = updatedApp.name
      )

      AuditHelper.calculateAppChanges(previousApp, partiallyUpdatedApp) shouldEqual Set(appNameAudit)
    }
  }
}
