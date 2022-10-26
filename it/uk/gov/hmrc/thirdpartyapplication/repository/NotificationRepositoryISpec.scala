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

package uk.gov.hmrc.thirdpartyapplication.repository

import org.scalatest.concurrent.Eventually
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{Notification, NotificationStatus, NotificationType}
import uk.gov.hmrc.thirdpartyapplication.util.{FixedClock, JavaDateTimeTestUtils, MetricsHelper}
import uk.gov.hmrc.utils.ServerBaseISpec

import java.time.{Clock, LocalDateTime}
import scala.concurrent.ExecutionContext.Implicits.global

class NotificationRepositoryISpec
    extends ServerBaseISpec
    with JavaDateTimeTestUtils
    with BeforeAndAfterEach
    with MetricsHelper
    with CleanMongoCollectionSupport
    with BeforeAndAfterAll
    with ApplicationStateUtil
    with Eventually
    with TableDrivenPropertyChecks
    with FixedClock {

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private val notificationRepository: NotificationRepository = app.injector.instanceOf[NotificationRepository]

  protected override def beforeEach(): Unit = {
    super.beforeEach()
    await(notificationRepository.collection.drop().toFuture())

    await(notificationRepository.ensureIndexes)
  }

  "createEntity" should {

    "create an entry" in {
      val applicationId = ApplicationId.random
      val now = LocalDateTime.now

      val result = await(notificationRepository.createEntity(Notification(applicationId, now, NotificationType.PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING, NotificationStatus.SENT)))

      result mustBe true
      await(notificationRepository.collection.countDocuments().toFuture().map(x => x.toInt)) mustBe 1
    }
  }

  "remove" should {
    "delete any records for the application id" in {
      val applicationId1  = ApplicationId.random
      val applicationId2  = ApplicationId.random
      val now = LocalDateTime.now
      await(notificationRepository.createEntity(Notification(applicationId1, now, NotificationType.PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING, NotificationStatus.SENT)))
      await(notificationRepository.createEntity(Notification(applicationId2, now, NotificationType.PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING, NotificationStatus.SENT)))

      val result = await(notificationRepository.deleteAllByApplicationId(applicationId1))

      result mustBe HasSucceeded
      await(notificationRepository.collection.countDocuments().toFuture().map(x => x.toInt)) mustBe 1
    }

    "not fail when deleting a non-existing record" in {
      val applicationId1  = ApplicationId.random
      val result = await(notificationRepository.deleteAllByApplicationId(applicationId1))

      result mustBe HasSucceeded
    }
  }
}
