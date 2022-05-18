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

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationState.{INITIAL, REMINDERS_SENT, ResponsibleIndividualVerificationState}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class ResponsibleIndividualVerificationRepositorySpec extends AsyncHmrcSpec
  with GuiceOneAppPerSuite
  with MongoSpecSupport
  with BeforeAndAfterEach with BeforeAndAfterAll {

  implicit val mat = app.materializer

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  val repo = new ResponsibleIndividualVerificationRepository(reactiveMongoComponent)

  override def beforeEach() {
    List(repo).foreach { db =>
      await(db.drop)
      await(db.ensureIndexes)
    }
  }

  override protected def afterAll() {
    List(repo).foreach { db =>
      await(db.drop)
    }
  }

  def buildDoc(state: ResponsibleIndividualVerificationState, createdOn: LocalDateTime = LocalDateTime.now) = {
    val doc = ResponsibleIndividualVerification(ResponsibleIndividualVerificationId.random, ApplicationId.random, Submission.Id.random, 0, UUID.randomUUID().toString, createdOn, state)
    await(repo.insert(doc))
    doc
  }

  val MANY_DAYS_AGO = 10
  val UPDATE_THRESHOLD = 5
  val FEW_DAYS_AGO = 1

  "fetchByStateAndAge" should {
    "retrieve correct documents" in {
      val initialWithOldDate = buildDoc(INITIAL, LocalDateTime.now.minusDays(MANY_DAYS_AGO))
      buildDoc(INITIAL, LocalDateTime.now.minusDays(FEW_DAYS_AGO))
      buildDoc(REMINDERS_SENT, LocalDateTime.now.minusDays(MANY_DAYS_AGO))
      buildDoc(REMINDERS_SENT, LocalDateTime.now.minusDays(FEW_DAYS_AGO))

      val results = await(repo.fetchByStateAndAge(INITIAL, LocalDateTime.now.minusDays(UPDATE_THRESHOLD)))

      results shouldEqual List(initialWithOldDate)
    }
  }

  "updateState" should {
    "change state correctly" in {
      val stateInitial = buildDoc(INITIAL)
      val stateReminderSent = buildDoc(REMINDERS_SENT)

      await(repo.updateState(stateInitial.id, REMINDERS_SENT))
      await(repo.updateState(stateReminderSent.id, REMINDERS_SENT))

      val allDocs = await(repo.findAll()).toSet
      allDocs shouldEqual Set(stateReminderSent, stateInitial.copy(state = REMINDERS_SENT))
    }
  }
}
