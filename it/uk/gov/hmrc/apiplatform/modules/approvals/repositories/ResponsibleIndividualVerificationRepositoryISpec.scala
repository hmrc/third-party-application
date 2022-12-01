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

package uk.gov.hmrc.apiplatform.modules.approvals.repositories

import cats.data.NonEmptyList
import org.scalatest.BeforeAndAfterEach
import play.api.inject
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{INITIAL, REMINDERS_SENT, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualToUVerification, ResponsibleIndividualUpdateVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ResponsibleIndividual
import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, UpdateApplicationEvent}
import uk.gov.hmrc.utils.ServerBaseISpec
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{CollaboratorActor, GatekeeperUserActor, ProductionAppNameChanged, ResponsibleIndividualVerificationStarted, ResponsibleIndividualChanged, ResponsibleIndividualSet, ResponsibleIndividualDeclined, ResponsibleIndividualDeclinedUpdate, ResponsibleIndividualDidNotVerify, ApplicationDeleted}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

import java.time.{Clock, LocalDateTime, ZoneOffset}
import java.util.UUID

class ResponsibleIndividualVerificationRepositoryISpec
    extends ServerBaseISpec
    with SubmissionsTestData
    with BeforeAndAfterEach
    with FixedClock {

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      ).overrides(inject.bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  val repository: ResponsibleIndividualVerificationRepository = app.injector.instanceOf[ResponsibleIndividualVerificationRepository]
  val responsibleIndividual = ResponsibleIndividual.build("Bob Fleming", "bob@fleming.com")
  val requestingAdminName = "Mr Admin"
  val requestingAdminEmail = "admin@fleming.com"

  override def beforeEach(): Unit = {
    await(repository.collection.drop().toFuture())
    await(repository.ensureIndexes)
  }

  def buildToUDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: LocalDateTime = LocalDateTime.now,
      submissionId: Submission.Id = Submission.Id.random,
      submissionIndex: Int = 0
    ) =
    ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId.random, ApplicationId.random, submissionId, submissionIndex, UUID.randomUUID().toString, createdOn, state)

  def buildUpdateDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: LocalDateTime = LocalDateTime.now,
      submissionId: Submission.Id = Submission.Id.random,
      submissionIndex: Int = 0
    ) =
    ResponsibleIndividualUpdateVerification(ResponsibleIndividualVerificationId.random, ApplicationId.random, submissionId, submissionIndex, UUID.randomUUID().toString, createdOn, responsibleIndividual, requestingAdminName, requestingAdminEmail, state)

  def buildAndSaveDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: LocalDateTime = LocalDateTime.now,
      submissionId: Submission.Id = Submission.Id.random,
      submissionIndex: Int = 0
    ) = {
    await(repository.save(buildToUDoc(state, createdOn, submissionId, submissionIndex)))
  }

  val MANY_DAYS_AGO    = 10
  val UPDATE_THRESHOLD = 5
  val FEW_DAYS_AGO     = 1

  "save" should {
    "save ToU document to the database" in {
      val doc = buildToUDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
      await(repository.save(doc))

      val allDocs = await(repository.findAll)
      allDocs mustBe List(doc)
    }

    "save update document to the database" in {
      val doc = buildUpdateDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
      await(repository.save(doc))

      val allDocs = await(repository.findAll)
      allDocs mustBe List(doc)
    }
  }

  "fetch" should {
    "retrieve a document by id" in {
      val savedDoc   = buildAndSaveDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
      val fetchedDoc = await(repository.fetch(savedDoc.id))

      Some(savedDoc) mustBe fetchedDoc
    }
  }

  "delete" should {
    "remove a document by id" in {
      val savedDoc = buildAndSaveDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
      await(repository.delete(savedDoc.id))

      await(repository.findAll) mustBe List()
    }

    "remove the record matching the latest submission instance only" in {
      val submissionId                   = Submission.Id.random
      val savedDocForSubmissionInstance0 = buildAndSaveDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO), submissionId, 0)
      buildAndSaveDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO), submissionId, 1)
      val submissionWithTwoInstances     = Submission.addInstance(answersToQuestions, Submission.Status.Answering(LocalDateTime.now, true))(aSubmission.copy(id = submissionId))
      await(repository.delete(submissionWithTwoInstances))

      await(repository.findAll) mustBe List(savedDocForSubmissionInstance0)
    }
  }

  "fetchByStateAndAge" should {
    "retrieve correct documents" in {
      val initialWithOldDate = buildAndSaveDoc(INITIAL, LocalDateTime.now.minusDays(MANY_DAYS_AGO))
      buildAndSaveDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
      buildAndSaveDoc(REMINDERS_SENT, LocalDateTime.now.minusDays(MANY_DAYS_AGO))
      buildAndSaveDoc(REMINDERS_SENT, LocalDateTime.now.minusDays(FEW_DAYS_AGO))

      val results = await(repository.fetchByTypeStateAndAge(ResponsibleIndividualVerification.VerificationTypeToU, INITIAL, LocalDateTime.now.minusDays(UPDATE_THRESHOLD)))

      results mustBe List(initialWithOldDate)
    }
  }

  "updateState" should {
    "change state correctly" in {
      val stateInitial      = buildAndSaveDoc(INITIAL)
      val stateReminderSent = buildAndSaveDoc(REMINDERS_SENT)

      await(repository.updateState(stateInitial.id, REMINDERS_SENT))
      await(repository.updateState(stateReminderSent.id, REMINDERS_SENT))

      val allDocs = await(repository.findAll).toSet
      allDocs mustBe Set(stateReminderSent, stateInitial.asInstanceOf[ResponsibleIndividualToUVerification].copy(state = REMINDERS_SENT))
    }
  }

  "updateSetDefaultVerificationType" should {
    "not change any records where verificationType already exists" in {
      val doc = buildUpdateDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
      val updateType = await(repository.save(doc))

      val stateInitial      = buildAndSaveDoc(INITIAL)
      val stateReminderSent = buildAndSaveDoc(REMINDERS_SENT)

      await(repository.updateSetDefaultVerificationType("termsOfUse"))

      val allDocs = await(repository.findAll).toSet
      allDocs mustBe Set(stateReminderSent, stateInitial, updateType)
    }
  }

  "applyEvents" should {
    val appName = "my app"
    val now = LocalDateTime.now(ZoneOffset.UTC)
    val appId = ApplicationId.random
    val code = "12341285217652137257396"

    def buildRiVerificationStartedEvent(submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualVerificationStarted(
        UpdateApplicationEvent.Id.random,
        appId,
        appName,
        now,
        CollaboratorActor("requester@example.com"),
        "ms admin",
        "admin@example.com",
        "ri name",
        "ri@example.com",
        submissionId,
        submissionIndex,
        ResponsibleIndividualVerificationId.random
      )

    def buildResponsibleIndividualChangedEvent(submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualChanged(
        UpdateApplicationEvent.Id.random,
        appId,
        now,
        CollaboratorActor("requester@example.com"),
        "Mr Previous Ri",
        "previous-ri@example.com",
        "Mr New Ri",
        "ri@example.com",
        submissionId,
        submissionIndex,
        code, 
        "Mr Admin",
        "admin@example.com"
      )

    def buildResponsibleIndividualDeclinedEvent(submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualDeclined(
        UpdateApplicationEvent.Id.random,
        appId,
        now,
        CollaboratorActor("requester@example.com"),
        "Mr New Ri",
        "ri@example.com",
        submissionId,
        submissionIndex,
        code, 
        "Mr Admin",
        "admin@example.com"
      )

    def buildResponsibleIndividualDeclinedUpdateEvent(submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualDeclinedUpdate(
        UpdateApplicationEvent.Id.random,
        appId,
        now,
        CollaboratorActor("requester@example.com"),
        "Mr New Ri",
        "ri@example.com",
        submissionId,
        submissionIndex,
        code, 
        "Mr Admin",
        "admin@example.com"
      )

    def buildResponsibleIndividualDidNotVerifyEvent(submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualDidNotVerify(
        UpdateApplicationEvent.Id.random,
        appId,
        now,
        CollaboratorActor("requester@example.com"),
        "Mr New Ri",
        "ri@example.com",
        submissionId,
        submissionIndex,
        code, 
        "Mr Admin",
        "admin@example.com"
      )

    def buildResponsibleIndividualSetEvent(submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualSet(
        UpdateApplicationEvent.Id.random,
        appId,
        now,
        CollaboratorActor("requester@example.com"),
        "Mr New Ri",
        "ri@example.com",
        submissionId,
        submissionIndex,
        code, 
        "Mr Admin",
        "admin@example.com"
      )

    def buildApplicationDeletedEvent(applicationId: ApplicationId) =
      ApplicationDeleted(
        UpdateApplicationEvent.Id.random,
        applicationId,
        now,
        CollaboratorActor("requester@example.com"),
        ClientId("clientId"),
        "wso2ApplicationName",
        "reasons",
        Some("admin@example.com")
      )

    def buildRiVerificationToURecord(id: ResponsibleIndividualVerificationId, submissionId: Submission.Id, submissionIndex: Int) =
      buildRiVerificationToURecordWithAppId(id, appId, submissionId, submissionIndex)

    def buildRiVerificationToURecordWithAppId(id: ResponsibleIndividualVerificationId, applicationId: ApplicationId, submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualToUVerification(
        id,
        applicationId,
        submissionId,
        submissionIndex,
        appName,
        now,
        INITIAL
      )

    def buildRiVerificationRecord(id: ResponsibleIndividualVerificationId, submissionId: Submission.Id, submissionIndex: Int) =
      buildRiVerificationRecordWithAppId(id, appId, submissionId, submissionIndex)

    def buildRiVerificationRecordWithAppId(id: ResponsibleIndividualVerificationId, applicationId: ApplicationId, submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualUpdateVerification(
        id,
        applicationId,
        submissionId,
        submissionIndex,
        appName,
        now,
        ResponsibleIndividual.build("ri name", "ri@example.com"),
        "ms admin",
        "admin@example.com",
        INITIAL
      )

    "handle ResponsibleIndividualVerificationStarted event correctly" in {
      val submissionId = Submission.Id.random
      val submissionIndex = 1
      val event = buildRiVerificationStartedEvent(submissionId, submissionIndex)

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      val expectedRecord = buildRiVerificationRecord(event.verificationId, submissionId, submissionIndex)
      await(repository.findAll) mustBe List(expectedRecord)
    }

    "remove old records that match submission when ResponsibleIndividualVerificationStarted event is received" in {
      val existingSubmissionId = Submission.Id.random
      val existingSubmissionIndex = 1

      val existingRecordMatchingSubmission = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, existingSubmissionId, existingSubmissionIndex)
      val existingRecordNotMatchingSubmissionId = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, Submission.Id.random, existingSubmissionIndex)
      val existingRecordNotMatchingSubmissionIndex = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, existingSubmissionId, existingSubmissionIndex + 1)

      await(repository.save(existingRecordMatchingSubmission))
      await(repository.save(existingRecordNotMatchingSubmissionId))
      await(repository.save(existingRecordNotMatchingSubmissionIndex))

      val updateTimestamp = LocalDateTime.now().plusHours(1)
      val event = buildRiVerificationStartedEvent(existingSubmissionId, existingSubmissionIndex).copy(eventDateTime = updateTimestamp)

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      val expectedNewRecord = existingRecordMatchingSubmission.copy(id = event.verificationId, createdOn = updateTimestamp)
      await(repository.findAll).toSet mustBe Set(existingRecordNotMatchingSubmissionId, existingRecordNotMatchingSubmissionIndex, expectedNewRecord)
    }

    "handle ResponsibleIndividualChanged event correctly" in {
      val submissionId = Submission.Id.random
      val submissionIndex = 1
      val event = buildResponsibleIndividualChangedEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId(code), submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      await(repository.findAll) mustBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualDeclined event correctly" in {
      val submissionId = Submission.Id.random
      val submissionIndex = 0
      val event = buildResponsibleIndividualDeclinedEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      await(repository.findAll) mustBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualDeclinedUpdate event correctly" in {
      val submissionId = Submission.Id.random
      val submissionIndex = 1
      val event = buildResponsibleIndividualDeclinedUpdateEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId(code), submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      await(repository.findAll) mustBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualDidNotVerify event correctly" in {
      val submissionId = Submission.Id.random
      val submissionIndex = 0
      val event = buildResponsibleIndividualDidNotVerifyEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      await(repository.findAll) mustBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualSet event correctly" in {
      val submissionId = Submission.Id.random
      val submissionIndex = 1
      val event = buildResponsibleIndividualSetEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId(code), submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      await(repository.findAll) mustBe List(existingRecordNotMatchingCode)
    }

    "remove old records that match application when ApplicationDeleted event is received" in {
      val existingAppId = ApplicationId.random

      val existingRecordMatchingApplication1 = buildRiVerificationRecordWithAppId(ResponsibleIndividualVerificationId.random, existingAppId, Submission.Id.random, 0)
      val existingRecordMatchingApplication2 = buildRiVerificationRecordWithAppId(ResponsibleIndividualVerificationId.random, existingAppId, Submission.Id.random, 1)
      val existingRecordMatchingApplication3 = buildRiVerificationToURecordWithAppId(ResponsibleIndividualVerificationId.random, existingAppId, Submission.Id.random, 0)
      val existingRecordNotMatchingApplication = buildRiVerificationRecordWithAppId(ResponsibleIndividualVerificationId.random, ApplicationId.random, Submission.Id.random, 0)

      await(repository.save(existingRecordMatchingApplication1))
      await(repository.save(existingRecordMatchingApplication2))
      await(repository.save(existingRecordMatchingApplication3))
      await(repository.save(existingRecordNotMatchingApplication))

      val event = buildApplicationDeletedEvent(existingAppId)

      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded

      await(repository.findAll).toSet mustBe Set(existingRecordNotMatchingApplication)
    }

    "handle other events correctly" in {
      val event = ProductionAppNameChanged(
        UpdateApplicationEvent.Id.random, applicationId, LocalDateTime.now(), GatekeeperUserActor("gkuser@example.com"), "app name", "new name", "admin@example.com"
      )
      await(repository.applyEvents(NonEmptyList.one(event))) mustBe HasSucceeded
      await(repository.findAll) mustBe List()
    }
  }
}
