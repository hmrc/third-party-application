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

    override def info(message: => String): Unit = infoMessages += message
    override def debug(message: => String): Unit = debugMessages += message
    override def warn(message: => String): Unit = warnMessages += message
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
        name = "",
        normalisedName = "",
        collaborators = Set.empty,
        wso2Username = wso2Username,
        wso2Password = wso2Password,
        wso2ApplicationName = wso2ApplicationName,
        tokens = ApplicationTokens(new EnvironmentToken("", "", "")),
        state = ApplicationState(),
        rateLimitTier = rateLimit)

    val mockApplicationRepository: ApplicationRepository = mock[ApplicationRepository]
    val mockWSO2ApiStoreConnector: Wso2ApiStoreConnector = mock[Wso2ApiStoreConnector]

    val stubLogger = new StubLogger
    val wso2ApplicationName = "foo"
    val wso2Username = UUID.randomUUID().toString
    val wso2Password = UUID.randomUUID().toString
    val wso2Cookie: String = UUID.randomUUID().toString

    when(mockWSO2ApiStoreConnector.login(wso2Username, wso2Password)).thenReturn(Future.successful(wso2Cookie))
    when(mockWSO2ApiStoreConnector.logout(wso2Cookie)).thenReturn(Future.successful(HasSucceeded))

    val config = ReconcileRateLimitsJobConfig(FiniteDuration(120, SECONDS), FiniteDuration(60, DAYS), enabled = true) // scalastyle:off magic.number
    val underTest = new ReconcileRateLimitsScheduledJob(mockLockKeeper, mockApplicationRepository, mockWSO2ApiStoreConnector, config, stubLogger)
  }

  "processJob" should {
    "call the processAll function on ApplicationRepository" in new Setup {
      when(mockApplicationRepository.processAll(any())).thenReturn(Future.successful())

      val result = await(underTest.runJob)

      verify(mockApplicationRepository).processAll(any())
    }
  }

  "reconcileApplicationRateLimit" should {
    "log at DEBUG when Rate Limits match" in new Setup {
      val wso2RateLimit: RateLimitTier = RateLimitTier.SILVER
      val tpaRateLimit: RateLimitTier = RateLimitTier.SILVER

      val application: ApplicationData = testApplication(Some(tpaRateLimit))
      val expectedMessage = s"Rate Limits in TPA and WSO2 match for Application [$wso2ApplicationName]."

      when(mockWSO2ApiStoreConnector.getApplicationRateLimitTier(matches(wso2Cookie), matches(wso2ApplicationName))(any[HeaderCarrier]))
        .thenReturn(Future.successful(wso2RateLimit))

      await(underTest.reconcileApplicationRateLimit(wso2Cookie, application))

      stubLogger.debugMessages.size should be (1)
      stubLogger.debugMessages.toList.head should be (expectedMessage)
    }

    "log at WARN when Rate Limits do not match" in new Setup {
      val wso2RateLimit: RateLimitTier = RateLimitTier.SILVER
      val tpaRateLimit: RateLimitTier = RateLimitTier.GOLD

      val application: ApplicationData = testApplication(Some(tpaRateLimit))
      val expectedMessage = s"Rate Limit mismatch for Application [$wso2ApplicationName]. TPA: [$tpaRateLimit], WSO2: [$wso2RateLimit]"

      when(mockWSO2ApiStoreConnector.getApplicationRateLimitTier(matches(wso2Cookie), matches(wso2ApplicationName))(any[HeaderCarrier]))
        .thenReturn(Future.successful(wso2RateLimit))

      await(underTest.reconcileApplicationRateLimit(wso2Cookie, application))

      stubLogger.warnMessages.size should be (1)
      stubLogger.warnMessages.toList.head should be (expectedMessage)
    }

    "handle case where TPA does not have a Rate Limit assigned" in new Setup {
      val wso2RateLimit: RateLimitTier = RateLimitTier.SILVER

      val application: ApplicationData = testApplication(None)
      val expectedMessage = s"No Rate Limit stored in TPA for Application [$wso2ApplicationName]. WSO2 Rate Limit is [$wso2RateLimit]"

      when(mockWSO2ApiStoreConnector.getApplicationRateLimitTier(matches(wso2Cookie), matches(wso2ApplicationName))(any[HeaderCarrier]))
        .thenReturn(Future.successful(wso2RateLimit))

      await(underTest.reconcileApplicationRateLimit(wso2Cookie, application))

      stubLogger.warnMessages.size should be (1)
      stubLogger.warnMessages.toList.head should be (expectedMessage)
    }
  }
}
