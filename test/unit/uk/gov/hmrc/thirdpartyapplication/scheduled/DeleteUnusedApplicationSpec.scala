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

import java.util
import java.util.UUID
import java.util.concurrent.TimeUnit.DAYS

import com.typesafe.config.{Config, ConfigFactory}
import org.joda.time.DateTime
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchersSugar}
import org.scalatest.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import play.api.{Configuration, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models.{Deleted, HasSucceeded}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.DeleteUnusedApplications
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import unit.uk.gov.hmrc.thirdpartyapplication.helpers.StubLogger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class DeleteUnusedApplicationSpec extends PlaySpec
  with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with FutureAwaits with DefaultAwaitTimeout {

  trait Setup {
    val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

    def fullConfiguration(cutoff: String, dryRun: Boolean): Config =
      ConfigFactory.parseString(
        s"""
           | DeleteUnusedApplications {
           |  startTime = "10:00",
           |  executionInterval = "1d",
           |  enabled = true,
           |  deleteApplicationsIfUnusedFor = $cutoff,
           |  dryRun = $dryRun
           | }
           |""".stripMargin)

    val stubLogger = new StubLogger
    val mockApplicationService: ApplicationService = mock[ApplicationService]
    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
  }

  trait FullRunSetup extends Setup {
    val cutoffDuration: FiniteDuration = new FiniteDuration(180, DAYS) // scalastyle:off magic.number
    val config: Config = fullConfiguration("180d", dryRun = false)

    val jobUnderTest: DeleteUnusedApplications =
      new DeleteUnusedApplications(new Configuration(config), mockApplicationService, mockApplicationRepository, reactiveMongoComponent) {
        override val logger: LoggerLike = stubLogger
      }
  }

  trait DryRunSetup extends Setup {
    val cutoffDuration: FiniteDuration = new FiniteDuration(90, DAYS) // scalastyle:off magic.number
    val config: Config = fullConfiguration("90d", dryRun = true)

    val jobUnderTest: DeleteUnusedApplications =
      new DeleteUnusedApplications(new Configuration(config), mockApplicationService, mockApplicationRepository, reactiveMongoComponent) {
        override val logger: LoggerLike = stubLogger
      }
  }

  "DeleteUnusedApplications Job" should {
    def expectedCutoffDate(cutoffDuration: FiniteDuration): DateTime = DateTime.now.minus(cutoffDuration.toMillis)

    "delete applications not used for more than a defined time" in new FullRunSetup {
      val application1Id: UUID = UUID.randomUUID()
      val application2Id: UUID = UUID.randomUUID()
      val applications: Set[UUID] = Set(application1Id, application2Id)

      val cutoffDateCaptor: ArgumentCaptor[DateTime] = ArgumentCaptor.forClass(classOf[DateTime])
      val applicationIdDeletionCaptor: ArgumentCaptor[UUID] = ArgumentCaptor.forClass(classOf[UUID])

      when(mockApplicationRepository.applicationsLastUsedBefore(cutoffDateCaptor.capture())).thenReturn(Future.successful(applications))
      when(mockApplicationService.deleteApplication(applicationIdDeletionCaptor.capture(), *, *)(*)).thenReturn(Future.successful(Deleted))

      await(jobUnderTest.runJob)

      expectedCutoffDate(cutoffDuration).getMillis must be (cutoffDateCaptor.getValue.getMillis +- 500)

      val capturedApplicationIds: util.List[UUID] = applicationIdDeletionCaptor.getAllValues
      capturedApplicationIds.size must be (applications.size)
      capturedApplicationIds must contain (application1Id)
      capturedApplicationIds must contain (application2Id)

      stubLogger.infoMessages must contain (s"Found ${applications.size} applications to delete")
    }

    "only log application ids if the job is set to dryRun" in new DryRunSetup {
      val application1Id: UUID = UUID.randomUUID()
      val application2Id: UUID = UUID.randomUUID()
      val applications: Set[UUID] = Set(application1Id, application2Id)

      val cutoffDateCaptor: ArgumentCaptor[DateTime] = ArgumentCaptor.forClass(classOf[DateTime])

      when(mockApplicationRepository.applicationsLastUsedBefore(cutoffDateCaptor.capture())).thenReturn(Future.successful(applications))
      when(mockApplicationRepository.delete(*)).thenReturn(Future.successful(HasSucceeded))

      await(jobUnderTest.runJob)

      expectedCutoffDate(cutoffDuration).getMillis must be (cutoffDateCaptor.getValue.getMillis +- 500)

      verifyNoInteractions(mockApplicationService)

      stubLogger.infoMessages must contain (s"Found ${applications.size} applications to delete")
      stubLogger.infoMessages must contain (s"[Dry Run] Would have deleted application with id [${application1Id.toString}]")
      stubLogger.infoMessages must contain (s"[Dry Run] Would have deleted application with id [${application2Id.toString}]")
    }
  }
}
