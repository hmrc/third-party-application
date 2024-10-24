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

import java.time.Instant
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
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.apiplatform.modules.applications.common.domain.models.FullName
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RedirectUri
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvents, EventId}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.services.{MarkAnswer, QuestionsAndAnswersToMap}
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.SubmissionsServiceMockModule
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

class AuditServiceSpec extends AsyncHmrcSpec with ApplicationStateUtil
    with ApplicationTestData with SubmissionsTestData with SubmissionsServiceMockModule {

  class Setup {
    val mockAuditConnector = mock[AuditConnector]
    val auditService       = new AuditService(mockAuditConnector, SubmissionsServiceMock.aMock, clock)
  }

  val timestamp             = instant
  val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

  val testImportantSubmissionData = ImportantSubmissionData(
    Some("organisationUrl.com"),
    responsibleIndividual,
    Set(ServerLocation.InUK),
    TermsAndConditionsLocations.InDesktopSoftware,
    PrivacyPolicyLocations.InDesktopSoftware,
    List.empty
  )

  val applicationData: StoredApplication = anApplicationData().copy(access = Access.Standard(importantSubmissionData = Some(testImportantSubmissionData)))
  val instigator                         = applicationData.collaborators.head.userId

  def isSameDataEvent(expected: DataEvent) =
    new ArgumentMatcher[DataEvent] {

      override def matches(de: DataEvent): Boolean = {
        de.auditSource == expected.auditSource &&
        de.auditType == expected.auditType &&
        de.tags == expected.tags &&
        de.detail == expected.detail
      }
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

      val tags                                   = Map("gatekeeperId" -> gatekeeperUser)
      val questionsWithAnswers                   = QuestionsAndAnswersToMap(declinedSubmission)
      val declinedData                           = Map("status" -> "declined", "reasons" -> reasons)
      val fmt                                    = DateTimeFormatter.ISO_DATE_TIME
      val submissionPreviousInstance             = declinedSubmission.instances.tail.head
      val submittedOn: Instant                   = submissionPreviousInstance.statusHistory.find(s => s.isSubmitted).map(_.timestamp).get
      val declinedOn: Instant                    = submissionPreviousInstance.statusHistory.find(s => s.isDeclined).map(_.timestamp).get
      val dates                                  = Map(
        "submission.started.date"                 -> fmt.format(declinedSubmission.startedOn.asLocalDateTime),
        "submission.submitted.date"               -> fmt.format(submittedOn.asLocalDateTime),
        "submission.declined.date"                -> fmt.format(declinedOn.asLocalDateTime),
        "responsibleIndividual.verification.date" -> nowAsText
      )
      val markedAnswers                          = MarkAnswer.markSubmission(declinedSubmission)
      val nbrOfFails                             = markedAnswers.filter(_._2 == Mark.Fail).size
      val nbrOfWarnings                          = markedAnswers.filter(_._2 == Mark.Warn).size
      val counters                               = Map(
        "submission.failures" -> nbrOfFails.toString,
        "submission.warnings" -> nbrOfWarnings.toString
      )
      val gatekeeperDetails: Map[String, String] = Map(
        "applicationId"          -> appInTesting.id.value.toString,
        "applicationName"        -> appInTesting.name.value,
        "upliftRequestedByEmail" -> appInTesting.state.requestedByEmailAddress.getOrElse("-"),
        "applicationAdmins"      -> appInTesting.admins.map(_.emailAddress.text).mkString(", ")
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

      val result = await(auditService.applyEvents(
        appInTesting.copy(access =
          Access.Standard(importantSubmissionData =
            Some(testImportantSubmissionData.copy(termsOfUseAcceptances =
              List(TermsOfUseAcceptance(ResponsibleIndividual(FullName("dave"), LaxEmailAddress("a@b.com")), instant, submissionId, submissionInstance = 0))
            ))
          )
        ),
        NonEmptyList.one(appApprovalRequestDeclined)
      ))
      result shouldBe Some(AuditResult.Success)
      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a single ApplicationApprovalRequestGranted event" in new Setup {

      val submission    = grantedSubmission
      val importSubData = testImportantSubmissionData.copy(termsOfUseAcceptances =
        List(TermsOfUseAcceptance(ResponsibleIndividual(FullName("dave"), LaxEmailAddress("a@b.com")), instant, submissionId, submissionInstance = 0))
      )

      val appInGKApproval = applicationData.copy(
        state = ApplicationStateExamples.pendingGatekeeperApproval("requestedByEmail", "requestedByName"),
        access = Access.Standard(importantSubmissionData = Some(importSubData))
      )

      val appApprovalRequestGranted = ApplicationEvents.ApplicationApprovalRequestGranted(
        EventId.random,
        applicationId,
        instant,
        Actors.GatekeeperUser(gatekeeperUser),
        SubmissionId.random,
        1,
        requesterName,
        requesterEmail.toLaxEmail
      )

      val tags                 = Map("gatekeeperId" -> gatekeeperUser)
      val questionsWithAnswers = QuestionsAndAnswersToMap(submission)
      val grantedData          = Map("status" -> "granted")
      val warningsData         = Map.empty[String, String]
      val escalatedData        = Map.empty[String, String]
      val fmt                  = DateTimeFormatter.ISO_DATE_TIME

      val submittedOn: Instant = submission.latestInstance.statusHistory.find(s => s.isSubmitted).map(_.timestamp).get
      val grantedOn: Instant   = submission.latestInstance.statusHistory.find(s => s.isGrantedWithOrWithoutWarnings).map(_.timestamp).get
      val rivd: Instant        =
        importSubData.termsOfUseAcceptances.find(t => (t.submissionId == submission.id && t.submissionInstance == submission.latestInstance.index)).map(_.dateTime).get

      val dates = Map(
        "submission.started.date"                 -> fmt.format(submission.startedOn.asLocalDateTime),
        "submission.submitted.date"               -> fmt.format(submittedOn.asLocalDateTime),
        "submission.granted.date"                 -> fmt.format(grantedOn.asLocalDateTime),
        "responsibleIndividual.verification.date" -> fmt.format(rivd.asLocalDateTime)
      )

      val markedAnswers                          = MarkAnswer.markSubmission(submission)
      val nbrOfFails                             = markedAnswers.filter(_._2 == Mark.Fail).size
      val nbrOfWarnings                          = markedAnswers.filter(_._2 == Mark.Warn).size
      val counters                               = Map(
        "submission.failures" -> nbrOfFails.toString,
        "submission.warnings" -> nbrOfWarnings.toString
      )
      val gatekeeperDetails: Map[String, String] = Map(
        "applicationId"          -> appInGKApproval.id.value.toString,
        "applicationName"        -> appInGKApproval.name.value,
        "upliftRequestedByEmail" -> appInGKApproval.state.requestedByEmailAddress.getOrElse("-"),
        "applicationAdmins"      -> appInGKApproval.admins.map(_.emailAddress.text).mkString(", ")
      )

      val extraData         = questionsWithAnswers ++ grantedData ++ warningsData ++ dates ++ counters ++ escalatedData ++ gatekeeperDetails
      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = ApplicationApprovalGranted.auditType,
        tags = hc.toAuditTags(ApplicationApprovalGranted.name, "-") ++ tags,
        detail = extraData
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))
      SubmissionsServiceMock.FetchLatest.thenReturn(submission)

      val result = await(auditService.applyEvents(
        appInGKApproval,
        NonEmptyList.one(appApprovalRequestGranted)
      ))
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
        newRedirectUris = List("https://new-url.example.com", "https://new-url.example.com/other-redirect").map(RedirectUri.unsafeApply(_))
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

    "applyEvents with a SandboxNameChanged event" in new Setup {

      val sandboxApplicationNameChanged = ApplicationEvents.SandboxApplicationNameChanged(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        oldName = "oldName",
        newName = "newName"
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = AppNameChanged.auditType,
        tags = hc.toAuditTags(AppNameChanged.name, "-"),
        detail = Map(
          "applicationId"      -> applicationId.value.toString,
          "oldApplicationName" -> "oldName",
          "newApplicationName" -> "newName"
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(sandboxApplicationNameChanged)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a SandboxApplicationPrivacyPolicyUrlChanged event" in new Setup {

      val sandboxApplicationNameChanged = ApplicationEvents.SandboxApplicationPrivacyPolicyUrlChanged(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        Some("anOldPrivacyPolicyUrl"),
        "aNewPrivacyPolicyUrl"
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = AppPrivacyPolicyUrlChanged.auditType,
        tags = hc.toAuditTags(AppPrivacyPolicyUrlChanged.name, "-"),
        detail = Map(
          "applicationId"       -> applicationId.value.toString,
          "newPrivacyPolicyUrl" -> "aNewPrivacyPolicyUrl"
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(sandboxApplicationNameChanged)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a SandboxApplicationPrivacyPolicyUrlRemoved event" in new Setup {

      val sandboxApplicationNameChanged = ApplicationEvents.SandboxApplicationPrivacyPolicyUrlRemoved(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        "anOldPrivacyPolicyUrl"
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = AppPrivacyPolicyUrlChanged.auditType,
        tags = hc.toAuditTags(AppPrivacyPolicyUrlChanged.name, "-"),
        detail = Map(
          "applicationId"       -> applicationId.value.toString,
          "newPrivacyPolicyUrl" -> ""
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(sandboxApplicationNameChanged)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a SandboxApplicationTermsAndConditionsUrlChanged event" in new Setup {

      val sandboxApplicationNameChanged = ApplicationEvents.SandboxApplicationTermsAndConditionsUrlChanged(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        Some("anOldTermsAndConditionsUrl"),
        "aNewTermsAndConditionsUrl"
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = AppTermsAndConditionsUrlChanged.auditType,
        tags = hc.toAuditTags(AppTermsAndConditionsUrlChanged.name, "-"),
        detail = Map(
          "applicationId"            -> applicationId.value.toString,
          "newTermsAndConditionsUrl" -> "aNewTermsAndConditionsUrl"
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(sandboxApplicationNameChanged)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a SandboxApplicationTermsAndConditionsUrlRemoved event" in new Setup {

      val sandboxApplicationNameChanged = ApplicationEvents.SandboxApplicationTermsAndConditionsUrlRemoved(
        EventId.random,
        applicationId,
        instant,
        collaboratorActor,
        "anOldTermsAndConditionsUrl"
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = AppTermsAndConditionsUrlChanged.auditType,
        tags = hc.toAuditTags(AppTermsAndConditionsUrlChanged.name, "-"),
        detail = Map(
          "applicationId"            -> applicationId.value.toString,
          "newTermsAndConditionsUrl" -> ""
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(sandboxApplicationNameChanged)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }

    "applyEvents with a ApplicationApprovalRequestSubmitted event" in new Setup {

      val applicationApprovalRequestSubmitted = ApplicationEvents.ApplicationApprovalRequestSubmitted(
        id = EventId.random,
        applicationId = applicationId,
        eventDateTime = instant,
        actor = collaboratorActor,
        submissionId = SubmissionId.random,
        submissionIndex = 0,
        requestingAdminName = "Mr Admin",
        requestingAdminEmail = LaxEmailAddress("admin@anycorp.com")
      )

      val expectedDataEvent = DataEvent(
        auditSource = "third-party-application",
        auditType = ApplicationUpliftRequested.auditType,
        tags = hc.toAuditTags(ApplicationUpliftRequested.name, "-"),
        detail = Map(
          "applicationId"      -> applicationId.value.toString,
          "newApplicationName" -> "MyApp"
        )
      )

      when(mockAuditConnector.sendEvent(*)(*, *)).thenReturn(Future.successful(AuditResult.Success))

      val result = await(auditService.applyEvents(applicationData, NonEmptyList.one(applicationApprovalRequestSubmitted)))

      result shouldBe Some(AuditResult.Success)

      verify(mockAuditConnector).sendEvent(argThat(isSameDataEvent(expectedDataEvent)))(*, *)
    }
  }
}
