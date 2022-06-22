package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import com.mongodb.MongoException
import org.scalatest.BeforeAndAfterEach
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{SingleChoiceAnswer, Submission}
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, FixedClock}
import uk.gov.hmrc.utils.ServerBaseISpec

import java.time.Clock
import scala.concurrent.ExecutionContext.Implicits.global

class SubmissionsDaoISpec
    extends ServerBaseISpec
    with FixedClock
    with ApplicationTestData
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
  val submissionsDao: SubmissionsDao               = app.injector.instanceOf[SubmissionsDao]

  override def beforeEach(): Unit = {
    await(submissionsRepository.collection.drop().toFuture())
    await(submissionsRepository.ensureIndexes)
  }

  "save and retrieved" should {

    "not find a record that is not there" in {
      await(submissionsDao.fetch(Submission.Id.random)) mustBe None
    }

    "store a record and retrieve it" in {
      await(submissionsDao.save(aSubmission)) mustBe aSubmission
      await(submissionsDao.fetch(aSubmission.id)).value mustBe aSubmission
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
      ) mustBe 1
    }
  }

  "fetchLatest" should {
    "find the only one" in {
      await(submissionsDao.save(aSubmission))
      await(submissionsDao.fetchLatest(applicationId)).value mustBe aSubmission
    }

    "find the latest one" in {
      await(submissionsDao.save(aSubmission))
      await(submissionsDao.save(altSubmission))
      await(submissionsDao.fetchLatest(applicationId)).value mustBe altSubmission
    }
  }

  "update" should {
    "replace the existing record" in {
      await(submissionsDao.save(aSubmission))

      val oldAnswers        = aSubmission.latestInstance.answersToQuestions
      val newAnswers        = oldAnswers + (questionId -> SingleChoiceAnswer("Yes"))
      val updatedSubmission = Submission.updateLatestAnswersTo(newAnswers)(aSubmission)

      await(submissionsDao.update(updatedSubmission)) mustBe updatedSubmission
      await(submissionsDao.fetchLatest(applicationId)).value mustBe updatedSubmission
    }

    "upsert submission if it doesn't exist " in {
      await(submissionsDao.update(aSubmission))

      await(submissionsDao.fetchLatest(applicationId)).value mustBe aSubmission
    }
  }
}
