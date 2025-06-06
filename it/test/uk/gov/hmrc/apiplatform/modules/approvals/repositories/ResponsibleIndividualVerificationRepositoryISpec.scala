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

package uk.gov.hmrc.apiplatform.modules.approvals.repositories

import java.time.{Clock, Duration, Instant}
import java.util.UUID

import cats.data.NonEmptyList
import org.scalatest.BeforeAndAfterEach

import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.common.utils._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.{ResponsibleIndividual, SubmissionId}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{INITIAL, REMINDERS_SENT, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{
  ResponsibleIndividualToUVerification,
  ResponsibleIndividualTouUpliftVerification,
  ResponsibleIndividualUpdateVerification,
  ResponsibleIndividualVerification,
  ResponsibleIndividualVerificationId
}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.EventId
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.util._

object ResponsibleIndividualVerificationRepositoryISpec extends FixedClock with CommonApplicationId {
  val appName = ApplicationName("my app")
  val code    = "12341285217652137257396"

  def buildRiVerificationStartedEvent(submissionId: SubmissionId, submissionIndex: Int) =
    ResponsibleIndividualVerificationStarted(
      EventId.random,
      applicationId,
      FixedClock.instant,
      Actors.AppCollaborator("requester@example.com".toLaxEmail),
      appName,
      "ms admin",
      "admin@example.com".toLaxEmail,
      "ri name",
      "ri@example.com".toLaxEmail,
      SubmissionId(submissionId.value),
      submissionIndex,
      ResponsibleIndividualVerificationId.random.value
    )

  def buildResponsibleIndividualChangedEvent(submissionId: SubmissionId, submissionIndex: Int) =
    ResponsibleIndividualChanged(
      EventId.random,
      applicationId,
      FixedClock.instant,
      Actors.AppCollaborator("requester@example.com".toLaxEmail),
      "Mr Previous Ri",
      "previous-ri@example.com".toLaxEmail,
      "Mr New Ri",
      "ri@example.com".toLaxEmail,
      SubmissionId(submissionId.value),
      submissionIndex,
      code,
      "Mr Admin",
      "admin@example.com".toLaxEmail
    )

  def buildResponsibleIndividualDeclinedEvent(submissionId: SubmissionId, submissionIndex: Int) = {
    ResponsibleIndividualDeclined(
      EventId.random,
      applicationId,
      FixedClock.instant,
      Actors.AppCollaborator("requester@example.com".toLaxEmail),
      "Mr New Ri",
      "ri@example.com".toLaxEmail,
      SubmissionId(submissionId.value),
      submissionIndex,
      code,
      "Mr Admin",
      "admin@example.com".toLaxEmail
    )
  }

  def buildResponsibleIndividualDeclinedUpdateEvent(submissionId: SubmissionId, submissionIndex: Int) =
    ResponsibleIndividualDeclinedUpdate(
      EventId.random,
      applicationId,
      FixedClock.instant,
      Actors.AppCollaborator("requester@example.com".toLaxEmail),
      "Mr New Ri",
      "ri@example.com".toLaxEmail,
      SubmissionId(submissionId.value),
      submissionIndex,
      code,
      "Mr Admin",
      "admin@example.com".toLaxEmail
    )

  def buildResponsibleIndividualDidNotVerifyEvent(submissionId: SubmissionId, submissionIndex: Int) =
    ResponsibleIndividualDidNotVerify(
      EventId.random,
      applicationId,
      FixedClock.instant,
      Actors.AppCollaborator("requester@example.com".toLaxEmail),
      "Mr New Ri",
      "ri@example.com".toLaxEmail,
      SubmissionId(submissionId.value),
      submissionIndex,
      code,
      "Mr Admin",
      "admin@example.com".toLaxEmail
    )

  def buildResponsibleIndividualSetEvent(submissionId: SubmissionId, submissionIndex: Int) =
    ResponsibleIndividualSet(
      EventId.random,
      applicationId,
      FixedClock.instant,
      Actors.AppCollaborator("requester@example.com".toLaxEmail),
      "Mr New Ri",
      "ri@example.com".toLaxEmail,
      SubmissionId(submissionId.value),
      submissionIndex,
      code,
      "Mr Admin",
      "admin@example.com".toLaxEmail
    )

  def buildRiVerificationToURecord(id: ResponsibleIndividualVerificationId, submissionId: SubmissionId, submissionIndex: Int) =
    buildRiVerificationToURecordWithAppId(id, applicationId, submissionId, submissionIndex)

  def buildRiVerificationToURecordWithAppId(id: ResponsibleIndividualVerificationId, applicationId: ApplicationId, submissionId: SubmissionId, submissionIndex: Int) =
    ResponsibleIndividualToUVerification(
      id,
      applicationId,
      submissionId,
      submissionIndex,
      appName,
      instant,
      INITIAL
    )

  def buildRiVerificationRecord(id: ResponsibleIndividualVerificationId, submissionId: SubmissionId, submissionIndex: Int) =
    buildRiVerificationRecordWithAppId(id, applicationId, submissionId, submissionIndex)

  def buildRiVerificationRecordWithAppId(id: ResponsibleIndividualVerificationId, applicationId: ApplicationId, submissionId: SubmissionId, submissionIndex: Int) =
    ResponsibleIndividualUpdateVerification(
      id,
      applicationId,
      submissionId,
      submissionIndex,
      appName,
      instant,
      ResponsibleIndividual.build("ri name", "ri@example.com"),
      "ms admin",
      "admin@example.com".toLaxEmail,
      INITIAL
    )
}

class ResponsibleIndividualVerificationRepositoryISpec
    extends ServerBaseISpec
    with SubmissionsTestData
    with BeforeAndAfterEach
    with FixedClock {

  import ResponsibleIndividualVerificationRepositoryISpec._

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      ).overrides(inject.bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  val repository: ResponsibleIndividualVerificationRepository = app.injector.instanceOf[ResponsibleIndividualVerificationRepository]
  val responsibleIndividual                                   = ResponsibleIndividual.build("Bob Fleming", "bob@fleming.com")
  val requestingAdminName                                     = "Mr Admin"
  val requestingAdminEmail                                    = "admin@fleming.com".toLaxEmail

  override def beforeEach(): Unit = {
    await(repository.collection.drop().toFuture())
    await(repository.ensureIndexes())
  }

  def buildToUDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: Instant = instant,
      submissionId: SubmissionId = SubmissionId.random,
      submissionIndex: Int = 0
    ) =
    ResponsibleIndividualToUVerification(
      ResponsibleIndividualVerificationId.random,
      ApplicationId.random,
      submissionId,
      submissionIndex,
      ApplicationName(UUID.randomUUID().toString),
      createdOn,
      state
    )

  def buildTouUpliftDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: Instant = instant,
      submissionId: SubmissionId = SubmissionId.random,
      submissionIndex: Int = 0
    ) =
    ResponsibleIndividualTouUpliftVerification(
      ResponsibleIndividualVerificationId.random,
      ApplicationId.random,
      submissionId,
      submissionIndex,
      ApplicationName(UUID.randomUUID().toString),
      createdOn,
      requestingAdminName,
      requestingAdminEmail,
      state
    )

  def buildUpdateDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: Instant = instant,
      submissionId: SubmissionId = SubmissionId.random,
      submissionIndex: Int = 0
    ) =
    ResponsibleIndividualUpdateVerification(
      ResponsibleIndividualVerificationId.random,
      ApplicationId.random,
      submissionId,
      submissionIndex,
      ApplicationName(UUID.randomUUID().toString),
      createdOn,
      responsibleIndividual,
      requestingAdminName,
      requestingAdminEmail,
      state
    )

  def buildAndSaveDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: Instant = instant,
      submissionId: SubmissionId = SubmissionId.random,
      submissionIndex: Int = 0
    ) = {
    await(repository.save(buildToUDoc(state, createdOn, submissionId, submissionIndex)))
  }

  def buildAndSaveTouUpliftDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: Instant = instant,
      submissionId: SubmissionId = SubmissionId.random,
      submissionIndex: Int = 0
    ) = {
    await(repository.save(buildTouUpliftDoc(state, createdOn, submissionId, submissionIndex)))
  }

  val MANY_DAYS_AGO    = 10
  val UPDATE_THRESHOLD = 5
  val FEW_DAYS_AGO     = 1

  "save" should {
    "save ToU document to the database" in {
      val doc = buildToUDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      await(repository.save(doc))

      val allDocs = await(repository.findAll)
      allDocs shouldBe List(doc)
    }

    "save ToU uplift document to the database" in {
      val doc = buildTouUpliftDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      await(repository.save(doc))

      val allDocs = await(repository.findAll)
      allDocs shouldBe List(doc)
    }

    "save update document to the database" in {
      val doc = buildUpdateDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      await(repository.save(doc))

      val allDocs = await(repository.findAll)
      allDocs shouldBe List(doc)
    }
  }

  "fetch" should {
    "retrieve a document by id" in {
      val savedDoc   = buildAndSaveDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      val fetchedDoc = await(repository.fetch(savedDoc.id))

      Some(savedDoc) shouldBe fetchedDoc
    }
  }

  "delete" should {
    "remove a document by id" in {
      val savedDoc = buildAndSaveDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      await(repository.delete(savedDoc.id))

      await(repository.findAll) shouldBe List()
    }

    "remove the record matching the latest submission instance only" in {
      val submissionId                   = SubmissionId.random
      val savedDocForSubmissionInstance0 = buildAndSaveDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)), submissionId, 0)
      buildAndSaveDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)), submissionId, 1)
      val submissionWithTwoInstances     = Submission.addInstance(answersToQuestions, Submission.Status.Answering(instant, true))(aSubmission.copy(id = submissionId))
      await(repository.delete(submissionWithTwoInstances))

      await(repository.findAll) shouldBe List(savedDocForSubmissionInstance0)
    }
  }

  "fetchByStateAndAge" should {
    "retrieve correct documents" in {
      val initialWithOldDate = buildAndSaveDoc(INITIAL, instant.minus(Duration.ofDays(MANY_DAYS_AGO)))
      buildAndSaveDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      buildAndSaveDoc(REMINDERS_SENT, instant.minus(Duration.ofDays(MANY_DAYS_AGO)))
      buildAndSaveDoc(REMINDERS_SENT, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))

      val results = await(repository.fetchByTypeStateAndAge(ResponsibleIndividualVerification.VerificationTypeToU, INITIAL, instant.minus(Duration.ofDays(UPDATE_THRESHOLD))))

      results shouldBe List(initialWithOldDate)
    }
  }

  "fetchByStateAgeAndTypes" should {
    "retrieve correct documents" in {
      val initialWithOldDate = buildAndSaveTouUpliftDoc(INITIAL, instant.minus(Duration.ofDays(MANY_DAYS_AGO)))
      buildAndSaveTouUpliftDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      buildAndSaveDoc(INITIAL, instant.minus(Duration.ofDays(MANY_DAYS_AGO)))
      buildAndSaveTouUpliftDoc(REMINDERS_SENT, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))

      val results = await(repository.fetchByStateAgeAndTypes(
        INITIAL,
        instant.minus(Duration.ofDays(UPDATE_THRESHOLD)),
        ResponsibleIndividualVerification.VerificationTypeUpdate,
        ResponsibleIndividualVerification.VerificationTypeTouUplift
      ))

      results shouldBe List(initialWithOldDate)
    }
  }

  "updateState" should {
    "change state correctly" in {
      val stateInitial      = buildAndSaveDoc(INITIAL)
      val stateReminderSent = buildAndSaveDoc(REMINDERS_SENT)

      await(repository.updateState(stateInitial.id, REMINDERS_SENT))
      await(repository.updateState(stateReminderSent.id, REMINDERS_SENT))

      val allDocs = await(repository.findAll).toSet
      allDocs shouldBe Set(stateReminderSent, stateInitial.asInstanceOf[ResponsibleIndividualToUVerification].copy(state = REMINDERS_SENT))
    }
  }

  "updateSetDefaultVerificationType" should {
    "not change any records where verificationType already exists" in {
      val doc        = buildUpdateDoc(INITIAL, instant.minus(Duration.ofDays(FEW_DAYS_AGO)))
      val updateType = await(repository.save(doc))

      val stateInitial      = buildAndSaveDoc(INITIAL)
      val stateReminderSent = buildAndSaveDoc(REMINDERS_SENT)

      await(repository.updateSetDefaultVerificationType("termsOfUse"))

      val allDocs = await(repository.findAll).toSet
      allDocs shouldBe Set(stateReminderSent, stateInitial, updateType)
    }
  }

  "deleteAllByApplicationId" should {

    "remove old records that match application when deleteAllByApplicationId" in {
      val existingAppId = ApplicationId.random

      val existingRecordMatchingApplication1   = buildRiVerificationRecordWithAppId(ResponsibleIndividualVerificationId.random, existingAppId, SubmissionId.random, 0)
      val existingRecordMatchingApplication2   = buildRiVerificationRecordWithAppId(ResponsibleIndividualVerificationId.random, existingAppId, SubmissionId.random, 1)
      val existingRecordMatchingApplication3   = buildRiVerificationToURecordWithAppId(ResponsibleIndividualVerificationId.random, existingAppId, SubmissionId.random, 0)
      val existingRecordNotMatchingApplication = buildRiVerificationRecordWithAppId(ResponsibleIndividualVerificationId.random, ApplicationId.random, SubmissionId.random, 0)

      await(repository.save(existingRecordMatchingApplication1))
      await(repository.save(existingRecordMatchingApplication2))
      await(repository.save(existingRecordMatchingApplication3))
      await(repository.save(existingRecordNotMatchingApplication))

      await(repository.deleteAllByApplicationId(existingAppId))

      await(repository.findAll).toSet shouldBe Set(existingRecordNotMatchingApplication)
    }
  }

  "applyEvents" should {
    "handle ResponsibleIndividualVerificationStarted event correctly" in {
      val submissionId    = SubmissionId.random
      val submissionIndex = 1
      val event           = buildRiVerificationStartedEvent(submissionId, submissionIndex)

      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded

      val expectedRecord = buildRiVerificationRecord(ResponsibleIndividualVerificationId(event.verificationId), submissionId, submissionIndex)
      await(repository.findAll) shouldBe List(expectedRecord)
    }

    "remove old records that match submission when ResponsibleIndividualVerificationStarted event is received" in {
      val existingSubmissionId    = SubmissionId.random
      val existingSubmissionIndex = 1

      val existingRecordMatchingSubmission         = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, existingSubmissionId, existingSubmissionIndex)
      val existingRecordNotMatchingSubmissionId    = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, SubmissionId.random, existingSubmissionIndex)
      val existingRecordNotMatchingSubmissionIndex = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, existingSubmissionId, existingSubmissionIndex + 1)

      await(repository.save(existingRecordMatchingSubmission))
      await(repository.save(existingRecordNotMatchingSubmissionId))
      await(repository.save(existingRecordNotMatchingSubmissionIndex))

      val updateTimestamp = instant.plus(Duration.ofHours(1))
      val event           = buildRiVerificationStartedEvent(existingSubmissionId, existingSubmissionIndex).copy(eventDateTime = updateTimestamp)

      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded

      val expectedNewRecord = existingRecordMatchingSubmission.copy(id = ResponsibleIndividualVerificationId(event.verificationId), createdOn = updateTimestamp)
      await(repository.findAll).toSet shouldBe Set(existingRecordNotMatchingSubmissionId, existingRecordNotMatchingSubmissionIndex, expectedNewRecord)
    }

    "handle ResponsibleIndividualChanged event correctly" in {
      val submissionId    = SubmissionId.random
      val submissionIndex = 1
      val event           = buildResponsibleIndividualChangedEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode    = buildRiVerificationRecord(ResponsibleIndividualVerificationId(code), submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded

      await(repository.findAll) shouldBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualDeclined event correctly" in {
      val submissionId    = SubmissionId.random
      val submissionIndex = 0
      val event           = buildResponsibleIndividualDeclinedEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode    = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded

      await(repository.findAll) shouldBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualDeclinedUpdate event correctly" in {
      val submissionId    = SubmissionId.random
      val submissionIndex = 1
      val event           = buildResponsibleIndividualDeclinedUpdateEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode    = buildRiVerificationRecord(ResponsibleIndividualVerificationId(code), submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded

      await(repository.findAll) shouldBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualDidNotVerify event correctly" in {
      val submissionId    = SubmissionId.random
      val submissionIndex = 0
      val event           = buildResponsibleIndividualDidNotVerifyEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode    = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationToURecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded

      await(repository.findAll) shouldBe List(existingRecordNotMatchingCode)
    }

    "handle ResponsibleIndividualSet event correctly" in {
      val submissionId    = SubmissionId.random
      val submissionIndex = 1
      val event           = buildResponsibleIndividualSetEvent(submissionId, submissionIndex)

      val existingRecordMatchingCode    = buildRiVerificationRecord(ResponsibleIndividualVerificationId(code), submissionId, 0)
      val existingRecordNotMatchingCode = buildRiVerificationRecord(ResponsibleIndividualVerificationId.random, submissionId, 1)

      await(repository.save(existingRecordMatchingCode))
      await(repository.save(existingRecordNotMatchingCode))

      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded

      await(repository.findAll) shouldBe List(existingRecordNotMatchingCode)
    }

    "handle other events correctly" in {
      val event = ProductionAppNameChangedEvent(
        EventId.random,
        applicationId,
        FixedClock.instant,
        Actors.GatekeeperUser("gkuser@example.com"),
        ApplicationName("app name"),
        ApplicationName("new name"),
        "admin@example.com".toLaxEmail
      )
      await(repository.applyEvents(NonEmptyList.one(event))) shouldBe HasSucceeded
      await(repository.findAll) shouldBe List()
    }
  }
}
