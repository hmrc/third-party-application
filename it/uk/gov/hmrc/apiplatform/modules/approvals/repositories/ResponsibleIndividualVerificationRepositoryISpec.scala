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
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{ADMIN_REQUESTED_CHANGE, INITIAL, REMINDERS_SENT, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, UpdateApplicationEvent}
import uk.gov.hmrc.utils.ServerBaseISpec
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{CollaboratorActor, ResponsibleIndividualVerificationStarted}
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

  override def beforeEach(): Unit = {
    await(repository.collection.drop().toFuture())
    await(repository.ensureIndexes)
  }

  def buildDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: LocalDateTime = LocalDateTime.now,
      submissionId: Submission.Id = Submission.Id.random,
      submissionIndex: Int = 0
    ) =
    ResponsibleIndividualVerification(ResponsibleIndividualVerificationId.random, ApplicationId.random, submissionId, submissionIndex, UUID.randomUUID().toString, createdOn, state)

  def buildAndSaveDoc(
      state: ResponsibleIndividualVerificationState,
      createdOn: LocalDateTime = LocalDateTime.now,
      submissionId: Submission.Id = Submission.Id.random,
      submissionIndex: Int = 0
    ) = {
    await(repository.save(buildDoc(state, createdOn, submissionId, submissionIndex)))
  }

  val MANY_DAYS_AGO    = 10
  val UPDATE_THRESHOLD = 5
  val FEW_DAYS_AGO     = 1

  "save" should {
    "save document to the database" in {
      val doc = buildDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
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

      val results = await(repository.fetchByStateAndAge(INITIAL, LocalDateTime.now.minusDays(UPDATE_THRESHOLD)))

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
      allDocs mustBe Set(stateReminderSent, stateInitial.copy(state = REMINDERS_SENT))
    }
  }

  "applyEvents" should {
    def buildRiVerificationEvent(submissionId: Submission.Id, submissionIndex: Int) =
      ResponsibleIndividualVerificationStarted(
        UpdateApplicationEvent.Id.random,
        ApplicationId.random,
        LocalDateTime.now(ZoneOffset.UTC),
        CollaboratorActor("requester@example.com"),
        "ri name",
        "ri@example.com",
        "my app",
        submissionId,
        submissionIndex,
        "admin@example.com"
    )

    "handle ResponsibleIndividualVerificationStarted event correctly" in {
      val event = buildRiVerificationEvent(Submission.Id.random, 1)
      val result = await(repository.applyEvents(NonEmptyList.one(event)))

      result.applicationId mustBe event.applicationId
      result.applicationName mustBe event.applicationName
      result.submissionId mustBe event.submissionId
      result.submissionInstance mustBe event.submissionIndex
      result.state mustBe ADMIN_REQUESTED_CHANGE
      result.createdOn mustBe event.eventDateTime

      await(repository.findAll) mustBe List(result)
    }

    "remove old records that match submission when ResponsibleIndividualVerificationStarted event is received" in {
      val existingSubmissionId = Submission.Id.random
      val existingSubmissionIndex = 1

      val existingRecordMatchingSubmission = buildDoc(ADMIN_REQUESTED_CHANGE, submissionId = existingSubmissionId, submissionIndex = existingSubmissionIndex)
      val existingRecordNotMatchingSubmissionId = buildDoc(ADMIN_REQUESTED_CHANGE, submissionId = Submission.Id.random, submissionIndex = existingSubmissionIndex)
      val existingRecordNotMatchingSubmissionIndex = buildDoc(ADMIN_REQUESTED_CHANGE, submissionId = existingSubmissionId, submissionIndex = 2)

      await(repository.save(existingRecordMatchingSubmission))
      await(repository.save(existingRecordNotMatchingSubmissionId))
      await(repository.save(existingRecordNotMatchingSubmissionIndex))

      val event = buildRiVerificationEvent(existingSubmissionId, existingSubmissionIndex)
      val newRecord = await(repository.applyEvents(NonEmptyList.one(event)))

      newRecord.applicationId mustBe event.applicationId
      newRecord.applicationName mustBe event.applicationName
      newRecord.submissionId mustBe existingSubmissionId
      newRecord.submissionInstance mustBe existingSubmissionIndex
      newRecord.state mustBe ADMIN_REQUESTED_CHANGE
      newRecord.createdOn mustBe event.eventDateTime

      await(repository.findAll).toSet mustBe Set(existingRecordNotMatchingSubmissionId, existingRecordNotMatchingSubmissionIndex, newRecord)
    }
  }
}
