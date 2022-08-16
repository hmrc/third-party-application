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

import org.scalatest.BeforeAndAfterEach
import play.api.inject
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{INITIAL, REMINDERS_SENT, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualToUVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.utils.ServerBaseISpec
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.util.FixedClock

import java.time.Clock
import java.time.LocalDateTime
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
    ResponsibleIndividualToUVerification(ResponsibleIndividualVerificationId.random, ApplicationId.random, submissionId, submissionIndex, UUID.randomUUID().toString, createdOn, state)

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
      allDocs mustBe Set(stateReminderSent, stateInitial.asInstanceOf[ResponsibleIndividualToUVerification].copy(state = REMINDERS_SENT))
    }
  }
}
