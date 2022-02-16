package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.mongo.MongoSpecSupport
import org.scalatest.BeforeAndAfterEach
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.thirdpartyapplication.repository.IndexVerification
import uk.gov.hmrc.thirdpartyapplication.util.MetricsHelper
import akka.actor.ActorSystem
import akka.stream.Materializer
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.MongoConnector
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import scala.concurrent.ExecutionContext
import reactivemongo.core.errors.DatabaseException
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

class SubmissionsDAOSpec
  extends AsyncHmrcSpec
    with MongoSpecSupport
    with BeforeAndAfterEach with BeforeAndAfterAll
    with IndexVerification
    with MetricsHelper
    with SubmissionsTestData {

  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = Materializer(s)

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val repo = new SubmissionsRepository(reactiveMongoComponent)
  private val dao = new SubmissionsDAO(repo)

  override def beforeEach() {
    List(repo).foreach { db =>
      await(db.drop)
      await(db.ensureIndexes)
    }
  }

  override protected def afterAll() {
    List(repo).foreach { db =>
      await(db.drop)
      await(s.terminate)
    }
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
      await(repo.count(implicitly[ExecutionContext])) shouldBe 1
    }
  }

  "fetchLastest" should {
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
