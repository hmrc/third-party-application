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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.UUID
import java.util.concurrent.TimeUnit.{DAYS, MINUTES}

import org.joda.time.{DateTime, Duration}
import org.mockito.{ArgumentCaptor, ArgumentMatchersSugar}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.slf4j
import play.api.LoggerLike
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{DeleteUnusedApplicationsJobConfig, DeleteUnusedApplicationsJobLockKeeper, DeleteUnusedApplicationsScheduledJob}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class DeleteUnusedApplicationsScheduledJobSpec extends PlaySpec
  with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with FutureAwaits with DefaultAwaitTimeout {

  trait Setup {
    class StubLogger extends LoggerLike {
      override val logger: slf4j.Logger = mock[slf4j.Logger]

      val infoMessages = new ListBuffer[String]()
      val debugMessages = new ListBuffer[String]()
      val warnMessages = new ListBuffer[String]()
      val errorMessages = new ListBuffer[String]()
      val capturedExceptions = new ListBuffer[Throwable]()

      override def info(message: => String): Unit = infoMessages += message
      override def debug(message: => String): Unit = debugMessages += message
      override def warn(message: => String): Unit = warnMessages += message
      override def error(message: => String): Unit = errorMessages += message
      override def error(message: => String, throwable: => Throwable): Unit = {
        errorMessages += message
        capturedExceptions += throwable
      }
    }

    private val reactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

    val mockLockKeeper: DeleteUnusedApplicationsJobLockKeeper = new DeleteUnusedApplicationsJobLockKeeper(reactiveMongoComponent) {
      override def lockId: String = "deleteUnusedApplicationsTestLock"
      override def repo: LockRepository = mock[LockRepository]
      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = body.map(value => Some(value))
    }

    val stubLogger = new StubLogger
    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
  }

  trait FullRunSetup extends Setup {
    val cutoffDuration: FiniteDuration = FiniteDuration(180, DAYS)
    val jobConfig: DeleteUnusedApplicationsJobConfig =
      DeleteUnusedApplicationsJobConfig(FiniteDuration(10, MINUTES), FiniteDuration(1, DAYS), enabled = true, cutoffDuration, dryRun = false)

    val jobUnderTest = new DeleteUnusedApplicationsScheduledJob(mockLockKeeper, mockApplicationRepository, jobConfig, stubLogger)
  }

  trait DryRunSetup extends Setup {
    val cutoffDuration: FiniteDuration = FiniteDuration(90, DAYS)
    val jobConfig: DeleteUnusedApplicationsJobConfig =
      DeleteUnusedApplicationsJobConfig(FiniteDuration(10, MINUTES), FiniteDuration(1, DAYS), enabled = true, cutoffDuration, dryRun = true)

    val jobUnderTest = new DeleteUnusedApplicationsScheduledJob(mockLockKeeper, mockApplicationRepository, jobConfig, stubLogger)
  }

  "Scheduled Job" should {
    def applicationsToReturn(applicationIds: UUID*): Seq[ApplicationData] =
      applicationIds.map(applicationId => {
        val application = mock[ApplicationData]
        when(application.id).thenReturn(applicationId)
        application
      })

    def expectedCutoffDate(cutoffDuration: FiniteDuration): DateTime = DateTime.now.minus(cutoffDuration.toMillis)

    "delete applications not used for more than a defined time" in new FullRunSetup {
      val application1Id: UUID = UUID.randomUUID()
      val application2Id: UUID = UUID.randomUUID()
      val applications: Seq[ApplicationData] = applicationsToReturn(application1Id, application2Id)

      val cutoffDateCaptor: ArgumentCaptor[DateTime] = ArgumentCaptor.forClass(classOf[DateTime])

      when(mockApplicationRepository.applicationsLastUsedBefore(cutoffDateCaptor.capture())).thenReturn(Future.successful(applications))
      when(mockApplicationRepository.delete(*)).thenReturn(Future.successful(HasSucceeded))

      await(jobUnderTest.runJob)

      verify(mockApplicationRepository, times(1)).delete(application1Id)
      verify(mockApplicationRepository, times(1)).delete(application2Id)

      expectedCutoffDate(cutoffDuration).getMillis must be (cutoffDateCaptor.getValue.getMillis +- 500)

      stubLogger.infoMessages must contain (s"Found ${applications.size} applications to delete")
    }

    "only log application ids if the job is set to dryRun" in new DryRunSetup {
      val application1Id: UUID = UUID.randomUUID()
      val application2Id: UUID = UUID.randomUUID()
      val applications: Seq[ApplicationData] = applicationsToReturn(application1Id, application2Id)

      val cutoffDateCaptor: ArgumentCaptor[DateTime] = ArgumentCaptor.forClass(classOf[DateTime])

      when(mockApplicationRepository.applicationsLastUsedBefore(cutoffDateCaptor.capture())).thenReturn(Future.successful(applications))
      when(mockApplicationRepository.delete(*)).thenReturn(Future.successful(HasSucceeded))

      await(jobUnderTest.runJob)

      verify(mockApplicationRepository, times(0)).delete(application1Id)
      verify(mockApplicationRepository, times(0)).delete(application2Id)

      expectedCutoffDate(cutoffDuration).getMillis must be (cutoffDateCaptor.getValue.getMillis +- 500)

      stubLogger.infoMessages must contain (s"Found ${applications.size} applications to delete")
      stubLogger.infoMessages must contain (s"[Dry Run] Would have deleted application with id [${application1Id.toString}]")
      stubLogger.infoMessages must contain (s"[Dry Run] Would have deleted application with id [${application2Id.toString}]")
    }
  }
}
