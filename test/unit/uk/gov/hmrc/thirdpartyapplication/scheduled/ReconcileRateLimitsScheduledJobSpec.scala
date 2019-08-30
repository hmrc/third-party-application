/*
 * Copyright 2019 HM Revenue & Customs
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
import java.util.concurrent.TimeUnit.{DAYS, SECONDS}

import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import org.joda.time.{DateTime, Duration}
import org.mockito.Matchers.{any, matches}
import org.mockito.Mockito._
import org.mockito.stubbing.OngoingStubbing
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import org.slf4j
import play.api.LoggerLike
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector.Wso2ApiStoreConnector
import uk.gov.hmrc.thirdpartyapplication.models
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationState, EnvironmentToken, HasSucceeded, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.{ReconcileRateLimitsJobConfig, ReconcileRateLimitsJobLockKeeper, ReconcileRateLimitsScheduledJob}
import uk.gov.hmrc.time.{DateTimeUtils => HmrcTime}

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class ReconcileRateLimitsScheduledJobSpec extends UnitSpec with MockitoSugar with MongoSpecSupport with BeforeAndAfterAll with ApplicationStateUtil {

  val FixedTimeNow: DateTime = HmrcTime.now
  val expiryTimeInDays = 90

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

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    val lockKeeperSuccess: () => Boolean = () => true

    private val reactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

    val mockLockKeeper: ReconcileRateLimitsJobLockKeeper = new ReconcileRateLimitsJobLockKeeper(reactiveMongoComponent) {
      override def lockId: String = "reconcileRateLimitsTestLock"

      override def repo: LockRepository = mock[LockRepository]

      override val forceLockReleaseAfter: Duration = Duration.standardMinutes(5) // scalastyle:off magic.number

      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        if (lockKeeperSuccess()) body.map(value => Future.successful(Some(value)))
        else Future.successful(None)
    }

    def testApplication(rateLimit: Option[RateLimitTier]): ApplicationData =
      new ApplicationData(
        id = UUID.randomUUID(),
        name = UUID.randomUUID().toString,
        normalisedName = "",
        collaborators = Set.empty,
        wso2Username = UUID.randomUUID().toString,
        wso2Password = UUID.randomUUID().toString,
        wso2ApplicationName = UUID.randomUUID().toString,
        tokens = ApplicationTokens(EnvironmentToken("", "", "")),
        state = ApplicationState(),
        rateLimitTier = rateLimit)

    def callToWSO2LoginReturns(wso2Username: String, wso2Password: String, returnValue: Future[String]): OngoingStubbing[Future[String]] =
      when(mockWSO2ApiStoreConnector.login(matches(wso2Username), matches(wso2Password))(any[HeaderCarrier])).thenReturn(returnValue)

    def callToWSO2LogoutReturns(wso2Cookie: String, returnValue: Future[HasSucceeded]): OngoingStubbing[Future[HasSucceeded]] =
      when(mockWSO2ApiStoreConnector.logout(matches(wso2Cookie))(any[HeaderCarrier])).thenReturn(returnValue)

    def callToFindAllApplicationsReturns(returnValue: Future[List[ApplicationData]]): OngoingStubbing[Future[List[ApplicationData]]] =
      when(mockApplicationRepository.findAll()).thenReturn(returnValue)

    def callToWSO2GetRateLimitTierReturns(wso2Cookie: String, wso2ApplicationName: String, returnValue: Future[RateLimitTier]): OngoingStubbing[Future[RateLimitTier]] =
      when(mockWSO2ApiStoreConnector.getApplicationRateLimitTier(matches(wso2Cookie), matches(wso2ApplicationName))(any[HeaderCarrier]))
        .thenReturn(returnValue)

    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
    val mockWSO2ApiStoreConnector: Wso2ApiStoreConnector = mock[Wso2ApiStoreConnector]

    val stubLogger = new StubLogger


    val config = ReconcileRateLimitsJobConfig(FiniteDuration(120, SECONDS), FiniteDuration(60, DAYS), enabled = true) // scalastyle:off magic.number
    val underTest = new ReconcileRateLimitsScheduledJob(mockLockKeeper, mockApplicationRepository, mockWSO2ApiStoreConnector, config, stubLogger)
  }

  "Scheduled Job" should {
    "log at DEBUG when Rate Limits match" in new Setup {
      val wso2RateLimit: RateLimitTier = RateLimitTier.SILVER
      val tpaRateLimit: RateLimitTier = RateLimitTier.SILVER

      val application: ApplicationData = testApplication(Some(tpaRateLimit))
      val wso2Cookie: String = UUID.randomUUID().toString

      val expectedMessage = s"Rate Limits for Application [${application.name} (${application.id})] are - TPA: [$tpaRateLimit], WSO2: [$wso2RateLimit] - MATCH"

      callToFindAllApplicationsReturns(Future.successful(List(application)))
      callToWSO2LoginReturns(application.wso2Username, application.wso2Password, Future.successful(wso2Cookie))
      callToWSO2GetRateLimitTierReturns(wso2Cookie, application.wso2ApplicationName, Future.successful(wso2RateLimit))
      callToWSO2LogoutReturns(wso2Cookie, Future.successful(HasSucceeded))

      await(underTest.runJob)

      stubLogger.debugMessages.size should be (1)
      stubLogger.debugMessages.toList.head should be (expectedMessage)
    }

    "log at WARN when Rate Limits do not match" in new Setup {
      val wso2RateLimit: RateLimitTier = RateLimitTier.SILVER
      val tpaRateLimit: RateLimitTier = RateLimitTier.GOLD

      val application: ApplicationData = testApplication(Some(tpaRateLimit))
      val wso2Cookie: String = UUID.randomUUID().toString

      val expectedMessage =
        s"Rate Limits for Application [${application.name} (${application.id})] are - TPA: [$tpaRateLimit], WSO2: [$wso2RateLimit] - MISMATCH"

      callToFindAllApplicationsReturns(Future.successful(List(application)))
      callToWSO2LoginReturns(application.wso2Username, application.wso2Password, Future.successful(wso2Cookie))
      callToWSO2GetRateLimitTierReturns(wso2Cookie, application.wso2ApplicationName, Future.successful(wso2RateLimit))
      callToWSO2LogoutReturns(wso2Cookie, Future.successful(HasSucceeded))

      await(underTest.runJob)

      stubLogger.warnMessages.size should be (1)
      stubLogger.warnMessages.toList.head should be (expectedMessage)
    }

    "handle case where TPA does not have a Rate Limit assigned" in new Setup {
      val wso2RateLimit: RateLimitTier = RateLimitTier.SILVER

      val application: ApplicationData = testApplication(None)
      val wso2Cookie: String = UUID.randomUUID().toString

      val expectedMessage = s"Rate Limits for Application [${application.name} (${application.id})] are - TPA: [NONE], WSO2: [$wso2RateLimit] - MISMATCH"

      callToFindAllApplicationsReturns(Future.successful(List(application)))
      callToWSO2LoginReturns(application.wso2Username, application.wso2Password, Future.successful(wso2Cookie))
      callToWSO2GetRateLimitTierReturns(wso2Cookie, application.wso2ApplicationName, Future.successful(wso2RateLimit))
      callToWSO2LogoutReturns(wso2Cookie, Future.successful(HasSucceeded))

      await(underTest.runJob)

      stubLogger.warnMessages.size should be (1)
      stubLogger.warnMessages.toList.head should be (expectedMessage)
    }

    "continue when a call to WSO2 login fails" in new Setup {
      val brokenApplication: ApplicationData = testApplication(Some(RateLimitTier.SILVER))
      val brokenApplicationExpectedMessage: String = s"Failed to process Application [${brokenApplication.name} (${brokenApplication.id})]"

      val workingApplicationRateLimit: models.RateLimitTier.Value = RateLimitTier.GOLD
      val workingApplication: ApplicationData = testApplication(Some(workingApplicationRateLimit))
      val workingApplicationCookie: String = UUID.randomUUID().toString
      val workingApplicationExpectedMessage =
        s"Rate Limits for Application [${workingApplication.name} (${workingApplication.id})] are - TPA: [$workingApplicationRateLimit], WSO2: [$workingApplicationRateLimit] - MATCH"

      callToFindAllApplicationsReturns(Future.successful(List(brokenApplication, workingApplication)))
      callToWSO2LoginReturns(brokenApplication.wso2Username, brokenApplication.wso2Password, Future.failed(new RuntimeException)) // Login Fails
      callToWSO2LoginReturns(workingApplication.wso2Username, workingApplication.wso2Password, Future.successful(workingApplicationCookie))
      callToWSO2GetRateLimitTierReturns(workingApplicationCookie, workingApplication.wso2ApplicationName, Future.successful(workingApplicationRateLimit))
      callToWSO2LogoutReturns(workingApplicationCookie, Future.successful(HasSucceeded))

      await(underTest.runJob)

      stubLogger.errorMessages.size should be (1)
      stubLogger.errorMessages.toList.head should be (brokenApplicationExpectedMessage)

      stubLogger.debugMessages.size should be (1)
      stubLogger.debugMessages.toList.head should be (workingApplicationExpectedMessage)
    }

    "continue when a call to WSO2 GetRateLimit fails" in new Setup {
      val brokenApplication: ApplicationData = testApplication(Some(RateLimitTier.SILVER))
      val brokenApplicationCookie: String = UUID.randomUUID().toString
      val brokenApplicationExpectedMessage: String = s"Failed to process Application [${brokenApplication.name} (${brokenApplication.id})]"

      val workingApplicationRateLimit: models.RateLimitTier.Value = RateLimitTier.GOLD
      val workingApplication: ApplicationData = testApplication(Some(workingApplicationRateLimit))
      val workingApplicationCookie: String = UUID.randomUUID().toString
      val workingApplicationExpectedMessage =
        s"Rate Limits for Application [${workingApplication.name} (${workingApplication.id})] are - TPA: [$workingApplicationRateLimit], WSO2: [$workingApplicationRateLimit] - MATCH"

      callToFindAllApplicationsReturns(Future.successful(List(brokenApplication, workingApplication)))
      callToWSO2LoginReturns(brokenApplication.wso2Username, brokenApplication.wso2Password, Future.successful(brokenApplicationCookie))
      callToWSO2GetRateLimitTierReturns(brokenApplicationCookie, brokenApplication.wso2ApplicationName, Future.failed(new RuntimeException))

      callToWSO2LoginReturns(workingApplication.wso2Username, workingApplication.wso2Password, Future.successful(workingApplicationCookie))
      callToWSO2GetRateLimitTierReturns(workingApplicationCookie, workingApplication.wso2ApplicationName, Future.successful(workingApplicationRateLimit))
      callToWSO2LogoutReturns(workingApplicationCookie, Future.successful(HasSucceeded))

      await(underTest.runJob)

      stubLogger.errorMessages.size should be (1)
      stubLogger.errorMessages.toList.head should be (brokenApplicationExpectedMessage)

      stubLogger.debugMessages.size should be (1)
      stubLogger.debugMessages.toList.head should be (workingApplicationExpectedMessage)
    }
  }
}
