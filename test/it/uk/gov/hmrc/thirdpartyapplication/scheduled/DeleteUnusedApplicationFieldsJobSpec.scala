package it.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.Duration
import org.scalatest.BeforeAndAfterEach
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{DeleteUnusedApplicationFieldsJob, DeleteUnusedApplicationFieldsJobLockKeeper}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class DeleteUnusedApplicationFieldsJobSpec extends AsyncHmrcSpec with MongoSpecSupport with BeforeAndAfterEach with ApplicationStateUtil {

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  implicit var s : ActorSystem = ActorSystem("test")
  implicit var m : Materializer = ActorMaterializer()
  val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  trait Setup {
    val lockKeeperSuccess: () => Boolean = () => true
    val mockLockKeeper: DeleteUnusedApplicationFieldsJobLockKeeper = new DeleteUnusedApplicationFieldsJobLockKeeper(reactiveMongoComponent) {
      //noinspection ScalaStyle
      override def lockId: String = null
      //noinspection ScalaStyle
      override def repo: LockRepository = null
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Some(value))
        else Future.successful(None)
    }

    val underTest = new DeleteUnusedApplicationFieldsJob(mockLockKeeper, applicationRepository)
  }

  override def beforeEach() {
    await(applicationRepository.drop)
  }

  override protected def afterEach() {
    await(applicationRepository.drop)
  }

  "DeleteUnusedApplicationFieldsJob" should {
    def createApplication(applicationId: UUID, clientSecrets: List[ClientSecret]): Future[ApplicationData] =
      applicationRepository.save(
        ApplicationData(
          applicationId,
          s"myApp-$applicationId",
          s"myapp-$applicationId",
          Set(Collaborator("user@example.com", Role.ADMINISTRATOR)),
          Some("description"),
          "myapplication",
          ApplicationTokens(
            EnvironmentToken("aaa", "ccc", clientSecrets)
          ),
          testingState(),
          Standard(List.empty, None, None),
          HmrcTime.now,
          Some(HmrcTime.now)))

    def addFieldToApplication(applicationId: UUID, fieldName: String, fieldValue: String) =
      applicationRepository.updateApplication(applicationId, Json.obj("$set" -> Json.obj(fieldName -> fieldValue)))

    def addSandboxToken(applicationId: UUID) =
      applicationRepository.updateApplication(
        applicationId,
        Json.obj(
          "$set" ->
            Json.obj(
              "tokens.sandbox" ->
                Json.obj("clientId" -> UUID.randomUUID(), "accessToken" -> UUID.randomUUID(), "clientSecrets" -> Json.arr()))))

    def fieldExistsInApplication(applicationId: UUID, fieldName: String): Future[Boolean] =
      applicationRepository.fetchWithProjection(
        Json.obj("id" -> applicationId, fieldName -> Json.obj("$exists" -> true)),
        Json.obj("_id" -> 0, "id" -> 1, fieldName -> 1))
        .map(_.size == 1)

    "delete wso2Username field" in new Setup {
      private val applicationId = UUID.randomUUID()

      await(createApplication(applicationId, List.empty))
      await(addFieldToApplication(applicationId, "wso2Username", "abcd1234"))

      await(fieldExistsInApplication(applicationId, "wso2Username")) should be (true)

      await(underTest.execute)

      await(fieldExistsInApplication(applicationId, "wso2Username")) should be (false)
    }

    "delete wso2Password field" in new Setup {
      private val applicationId = UUID.randomUUID()

      await(createApplication(applicationId, List.empty))
      await(addFieldToApplication(applicationId, "wso2Password", "abcd1234"))

      await(fieldExistsInApplication(applicationId, "wso2Password")) should be (true)

      await(underTest.execute)

      await(fieldExistsInApplication(applicationId, "wso2Password")) should be (false)
    }

    "delete sandbox token sub-document" in new Setup {
      private val applicationId = UUID.randomUUID()

      await(createApplication(applicationId, List.empty))
      await(addSandboxToken(applicationId))

      await(fieldExistsInApplication(applicationId, "tokens.sandbox")) should be (true)

      await(underTest.execute)

      await(fieldExistsInApplication(applicationId, "tokens.sandbox")) should be (false)
    }
  }
}
