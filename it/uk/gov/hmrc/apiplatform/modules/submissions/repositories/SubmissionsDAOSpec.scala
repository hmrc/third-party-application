package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import reactivemongo.core.errors.DatabaseException
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, MetricsHelper}

import scala.concurrent.ExecutionContext.Implicits.global

class SubmissionsDAOSpec
  extends AsyncHmrcSpec
    with MongoSupport
    with CleanMongoCollectionSupport
    with BeforeAndAfterEach with BeforeAndAfterAll
    with MetricsHelper
    with SubmissionsTestData {

  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = Materializer(s)

  private val repo = new SubmissionsRepository(mongoComponent)
  private val dao = new SubmissionsDAO(repo)

  override protected def afterAll(): Unit = {
    super.afterAll()
    await(s.terminate())
  }

  "save and retrieved" should {
    "not find a record that is not there" in {
      await(dao.fetch(Submission.Id.random)) shouldBe None
    }

    "store a record and retrieve it" in {
      await(dao.save(aSubmission)) shouldBe aSubmission
      await(dao.fetch(aSubmission.id)).value shouldBe aSubmission
    }

    "not store multiple records of the same submission id" in {
      await(dao.save(aSubmission)) shouldBe aSubmission
      intercept[DatabaseException] {
        await(dao.save(aSubmission))
      }
      await(repo.collection.countDocuments().toFuture().map(x => x.toInt)) shouldBe 1
    }
  }

  "fetchLatest" should {
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
  }
}
