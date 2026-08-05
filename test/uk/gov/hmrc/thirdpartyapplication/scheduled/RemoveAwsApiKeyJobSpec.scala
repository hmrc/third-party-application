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

package uk.gov.hmrc.thirdpartyapplication.scheduled

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.stream.Materializer
import org.scalatest.concurrent.Eventually

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, MongoSupport}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, ClientId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationStateFixtures
import uk.gov.hmrc.thirdpartyapplication.mocks.ApiGatewayStoreMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.util._

class RemoveAwsApiKeyJobSpec
    extends AsyncHmrcSpec
    with MongoSupport
    with CleanMongoCollectionSupport
    with ApplicationStateFixtures
    with NoMetricsGuiceOneAppPerSuite
    with StoredApplicationFixtures
    with CollaboratorTestData
    with Eventually {

  implicit val m: Materializer   = app.materializer
  implicit val metrics: Metrics  = app.injector.instanceOf[Metrics]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    await(mongoDatabase.drop().toFuture())
  }

  trait Setup extends ApiGatewayStoreMockModule {
    val applicationRepository                    = new ApplicationRepository(mongoComponent, metrics, FixedClock.clock)
    val lockKeeperSuccess: () => Boolean         = () => true
    val mongoLockRepository: MongoLockRepository = app.injector.instanceOf[MongoLockRepository]

    val mockRemoveAwsApiKeyJobLockService: RemoveAwsApiKeyJobLockService =
      new RemoveAwsApiKeyJobLockService(mongoLockRepository) {
        override val ttl: Duration = 1.minutes

        override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
          if (lockKeeperSuccess()) body.map(value => Some(value))(ec) else Future.successful(None)
      }

    val mockApiGateway = ApiGatewayStoreMock.aMock
  }

  trait DryRunSetup extends Setup {
    val jobConfig: RemoveAwsApiKeyJobConfig = RemoveAwsApiKeyJobConfig(enabled = true, dryRun = true)
    val underTest                           = new RemoveAwsApiKeyJob(mockRemoveAwsApiKeyJobLockService, applicationRepository, mockApiGateway, jobConfig)
  }

  trait RemoveApiKeySetup extends Setup {
    val jobConfig: RemoveAwsApiKeyJobConfig = RemoveAwsApiKeyJobConfig(enabled = true, dryRun = false)
    val underTest                           = new RemoveAwsApiKeyJob(mockRemoveAwsApiKeyJobLockService, applicationRepository, mockApiGateway, jobConfig)
  }

  "RemoveAwsApiKeyJob" should {
    def loadDatabase(repo: ApplicationRepository): Unit = {
      val bulkInsert = List(
        anApplicationData(wso2ApplicationName = "name1"),
        anApplicationData(wso2ApplicationName = "name2"),
        anApplicationData(wso2ApplicationName = "name3"),
        anApplicationData(wso2ApplicationName = ""),
        anApplicationData(wso2ApplicationName = "   ")
      )

      await(Future.sequence(bulkInsert.map(i => repo.save(i))))
    }

    "remove AWS API keys" in new RemoveApiKeySetup {
      loadDatabase(applicationRepository)

      await(underTest.runJob)

      ApiGatewayStoreMock.DeleteApplication.verifyCalledWith("name1")
      ApiGatewayStoreMock.DeleteApplication.verifyCalledWith("name2")
      ApiGatewayStoreMock.DeleteApplication.verifyCalledWith("name3")
      ApiGatewayStoreMock.verifyNoMoreInteractions()
    }

    "not remove AWS keys if dryRun option is specified" in new DryRunSetup {
      loadDatabase(applicationRepository)

      await(underTest.runJob)

      ApiGatewayStoreMock.verifyZeroInteractions()
    }
  }

  def anApplicationData(
      id: ApplicationId = ApplicationId.random,
      wso2ApplicationName: String
    ): StoredApplication =
    storedApp
      .withId(ApplicationId.random)
      .copy(
        wso2ApplicationName = wso2ApplicationName,
        tokens = storedApp.tokens.copy(
          production = storedApp.tokens.production.copy(
            clientId = ClientId(id.toString())
          )
        )
      )
}
