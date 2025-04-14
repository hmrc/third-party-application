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

package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import java.time.Clock
import scala.concurrent.ExecutionContext.Implicits.global

import com.mongodb.MongoException
import org.scalatest.BeforeAndAfterEach

import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{ActualAnswer, Submission}
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.util._

class SubmissionsDAOISpec
    extends ServerBaseISpec
    with FixedClock
    with StoredApplicationFixtures
    with SubmissionsTestData
    with BeforeAndAfterEach {

  protected override def appBuilder: GuiceApplicationBuilder =
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])

  val submissionsRepository: SubmissionsRepository = app.injector.instanceOf[SubmissionsRepository]
  val submissionsDao: SubmissionsDAO               = app.injector.instanceOf[SubmissionsDAO]

  override def beforeEach(): Unit = {
    await(submissionsRepository.collection.drop().toFuture())
    await(submissionsRepository.ensureIndexes())
  }

  "save and retrieved" should {

    "not find a record that is not there" in {
      await(submissionsDao.fetch(SubmissionId.random)) shouldBe None
    }

    "store a record and retrieve it" in {
      await(submissionsDao.save(aSubmission)) shouldBe aSubmission
      await(submissionsDao.fetch(aSubmission.id)).value shouldBe aSubmission
    }

    "not store multiple records of the same submission id" in {
      await(submissionsDao.save(aSubmission))

      intercept[MongoException] {
        await(submissionsDao.save(aSubmission))
      }

      await(
        submissionsRepository.collection
          .countDocuments()
          .toFuture()
          .map(x => x.toInt)
      ) shouldBe 1
    }
  }

  "fetchLatest" should {
    "find the only one" in {
      await(submissionsDao.save(aSubmission))
      await(submissionsDao.fetchLatest(applicationId)).value shouldBe aSubmission
    }

    "find the latest one" in {
      await(submissionsDao.save(aSubmission))
      await(submissionsDao.save(altSubmission))
      await(submissionsDao.fetchLatest(applicationId)).value shouldBe altSubmission
    }
  }

  "update" should {
    "replace the existing record" in {
      await(submissionsDao.save(aSubmission))

      val oldAnswers        = aSubmission.latestInstance.answersToQuestions
      val newAnswers        = oldAnswers + (questionId -> ActualAnswer.SingleChoiceAnswer("Yes"))
      val updatedSubmission = Submission.updateLatestAnswersTo(newAnswers)(aSubmission)

      await(submissionsDao.update(updatedSubmission)) shouldBe updatedSubmission
      await(submissionsDao.fetchLatest(applicationId)).value shouldBe updatedSubmission
    }

    "upsert submission if it doesn't exist " in {
      await(submissionsDao.update(aSubmission))

      await(submissionsDao.fetchLatest(applicationId)).value shouldBe aSubmission
    }
  }
}
