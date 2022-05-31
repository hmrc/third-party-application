package uk.gov.hmrc.apiplatform.modules.approvals.repositories

import com.mongodb.MongoException
import org.scalatest.BeforeAndAfterEach
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerification
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.Submission
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, FixedClock}
import uk.gov.hmrc.utils.ServerBaseISpec

import java.time.Clock
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ResponsibleIndividualVerificationDaoISpec
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

  val responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository =
    app.injector.instanceOf[ResponsibleIndividualVerificationRepository]
  val responsibleIndividualVerificationDao: ResponsibleIndividualVerificationDao = app.injector.instanceOf[ResponsibleIndividualVerificationDao]

  override def beforeEach(): Unit = {
    await(responsibleIndividualVerificationRepository.collection.drop().toFuture())
    await(responsibleIndividualVerificationRepository.ensureIndexes)
  }

  trait Setup {
    val responsibleIndividualVerification = ResponsibleIndividualVerification(
      applicationId = ApplicationId.random,
      submissionId = Submission.Id.random,
      submissionInstance = 1,
      applicationName = "Hello World")
  }

  "save and retrieve" should {

    "not find a record that is not there" in new Setup {
      await(responsibleIndividualVerificationDao.fetch(responsibleIndividualVerification.id)) mustBe None
    }

    "store a record and retrieve it" in new Setup {
      await(responsibleIndividualVerificationDao.save(responsibleIndividualVerification))
      await(responsibleIndividualVerificationDao.fetch(responsibleIndividualVerification.id)).value mustBe responsibleIndividualVerification
    }

    "not store multiple records of the same responsibleIndividualVerification id" in new Setup {
      await(responsibleIndividualVerificationDao.save(responsibleIndividualVerification))

      intercept[MongoException] {
        await(responsibleIndividualVerificationDao.save(responsibleIndividualVerification))
      }

      await(getDocumentsCount) mustBe 1
    }
  }

  "delete" should {
    "find the only one" in new Setup {
      await(responsibleIndividualVerificationDao.save(responsibleIndividualVerification))
      await(getDocumentsCount) mustBe 1

      await(responsibleIndividualVerificationDao.delete(responsibleIndividualVerification.id)) mustBe 1
      await(getDocumentsCount) mustBe 0
    }
  }

  private def getDocumentsCount: Future[Int] = {
    responsibleIndividualVerificationRepository.collection
      .countDocuments()
      .toFuture()
      .map(x => x.toInt)
  }
}
