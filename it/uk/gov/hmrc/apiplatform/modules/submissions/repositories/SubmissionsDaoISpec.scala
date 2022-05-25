package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import com.mongodb.MongoException
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.ExecutionContext.Implicits.global

class SubmissionsDaoISpec
  extends AsyncHmrcSpec
    with MongoSupport
    with CleanMongoCollectionSupport
    with SubmissionsTestData
    with GuiceOneAppPerSuite {

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure(
        "metrics.enabled" -> true,
        "metrics.jvm" -> false,
        "Test.auditing.enabled" -> true,
        "mongodb.uri" -> s"mongodb://127.0.0.1:27017/test-${this.getClass.getSimpleName}"
      )
      .disable(classOf[SchedulerModule])
      .overrides(bind[Clock].toInstance(Clock.fixed(Instant.now(), ZoneId.systemDefault())))
      .build()
  }

  override lazy val app: Application = fakeApplication()

  val repo: SubmissionsRepository = new SubmissionsRepository(mongoComponent)
  val dao = new SubmissionsDao(repo)

  protected override def beforeEach(): Unit = {
    await(mongoDatabase.drop().toFuture())
  }

  "save and retrieved" should {
    /*"not find a record that is not there" in {
      await(dao.fetch(Submission.Id.random)) shouldBe None
    }

    "store a record and retrieve it" in {
      await(dao.save(aSubmission)) shouldBe aSubmission
      await(dao.fetch(aSubmission.id)).value shouldBe aSubmission
    }
*/
    "not store multiple records of the same submission id" in {
      await(dao.save(aSubmission)) shouldBe aSubmission

      intercept[MongoException] {
        await(repo.collection.insertOne(aSubmission).toFuture())
      }

//      println(s"******* ${exception}")

      await(repo.collection.countDocuments().toFuture().map(x => x.toInt)) shouldBe 1
    }
  }

  /*"fetchLatest" should {
    "find the only one" in {
      await(dao.save(aSubmission))
      await(dao.fetchLatest(applicationId)).value shouldBe aSubmission
    }

    "find the latest one" in {
      await(dao.save(aSubmission))
      await(dao.save(altSubmission))
      await(dao.fetchLatest(applicationId)).value shouldBe altSubmission
    }
  }

  "update" should {
    "replace the existing record" in {
      await(dao.save(aSubmission))
      val oldAnswers = aSubmission.latestInstance.answersToQuestions
      val newAnswers = oldAnswers + (questionId -> SingleChoiceAnswer("Yes"))
      val updatedSubmission = Submission.updateLatestAnswersTo(newAnswers)(aSubmission)
      await(dao.update(updatedSubmission)) shouldBe updatedSubmission
      await(dao.fetchLatest(applicationId)).value shouldBe updatedSubmission
    }
  }*/


}
