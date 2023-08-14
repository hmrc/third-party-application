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

package uk.gov.hmrc.thirdpartyapplication.repository

import org.scalatest.concurrent.Eventually
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.models.db.TermsOfUseInvitation
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState.{EMAIL_SENT, TERMS_OF_USE_V2}
import uk.gov.hmrc.thirdpartyapplication.util.{JavaDateTimeTestUtils, MetricsHelper}
import uk.gov.hmrc.utils.ServerBaseISpec
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

class TermsOfUseInvitationRepositoryISpec
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

  private val termsOfUseInvitationRepository: TermsOfUseInvitationRepository = app.injector.instanceOf[TermsOfUseInvitationRepository]

  protected override def beforeEach(): Unit = {
    super.beforeEach()
    await(termsOfUseInvitationRepository.collection.drop().toFuture())

    await(termsOfUseInvitationRepository.ensureIndexes)
  }

  "create" should {
    "create an entry" in {
      val applicationId = ApplicationId.random
      val now           = Instant.now(clock)
      val touInvite     = TermsOfUseInvitation(applicationId, now, now, now, None, EMAIL_SENT)

      val result = await(termsOfUseInvitationRepository.create(touInvite))

      result mustBe Some(touInvite)
      await(termsOfUseInvitationRepository.collection.countDocuments().toFuture().map(x => x.toInt)) mustBe 1
    }
  }

  "fetch" should {
    "fetch an entry" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val now            = Instant.now(clock)
      val touInvite1     = TermsOfUseInvitation(applicationId1, now, now, now, None, EMAIL_SENT)
      val touInvite2     = TermsOfUseInvitation(applicationId2, now, now, now, None, EMAIL_SENT)

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      val result = await(termsOfUseInvitationRepository.fetch(applicationId1))

      result mustBe Some(touInvite1)
    }
  }

  "fetchAll" should {
    "fetch an entry" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val now            = Instant.now(clock)
      val touInvite1     = TermsOfUseInvitation(applicationId1, now, now, now, None, EMAIL_SENT)
      val touInvite2     = TermsOfUseInvitation(applicationId2, now, now, now, None, TERMS_OF_USE_V2)

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      val result = await(termsOfUseInvitationRepository.fetchAll())

      result.size mustBe 2
    }
  }

  "fetchByStatus" should {
    "fetch an entry" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val now            = Instant.now(clock)
      val touInvite1     = TermsOfUseInvitation(applicationId1, now, now, now, None, EMAIL_SENT)
      val touInvite2     = TermsOfUseInvitation(applicationId2, now, now, now, None, TERMS_OF_USE_V2)

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      val result = await(termsOfUseInvitationRepository.fetchByStatus(EMAIL_SENT))

      result.size mustBe 1
      result.head mustBe touInvite1
    }
  }

  "fetchByStatusBeforeDueBy" should {
    "fetch an entry" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val startDate1     = Instant.parse("2023-06-01T12:01:02.000Z")
      val dueBy1         = startDate1.plus(60, ChronoUnit.DAYS)
      val startDate2     = Instant.parse("2023-06-14T12:02:04.000Z")
      val dueBy2         = startDate2.plus(60, ChronoUnit.DAYS)
      val startDate3     = Instant.parse("2023-06-28T12:03:06.000Z")
      val dueBy3         = startDate3.plus(60, ChronoUnit.DAYS)

      val touInvite1     = TermsOfUseInvitation(applicationId1, startDate1, startDate1, dueBy1, None, EMAIL_SENT)
      val touInvite2     = TermsOfUseInvitation(applicationId2, startDate2, startDate2, dueBy2, None, EMAIL_SENT)
      val touInvite3     = TermsOfUseInvitation(applicationId3, startDate3, startDate3, dueBy3, None, TERMS_OF_USE_V2)

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      val findDueBy      = Instant.parse("2023-08-03T12:00:00.000Z")
      val result = await(termsOfUseInvitationRepository.fetchByStatusBeforeDueBy(EMAIL_SENT, findDueBy))

      result.size mustBe 1
      result.head mustBe touInvite1
    }
  }

  "updateState" should {
    "update the status of an existing entry" in {
      val applicationId = ApplicationId.random
      val now           = Instant.now(clock)
      val touInvite     = TermsOfUseInvitation(applicationId, now, now, now, None, EMAIL_SENT)

      await(termsOfUseInvitationRepository.create(touInvite))
      val result = await(termsOfUseInvitationRepository.updateState(applicationId, TERMS_OF_USE_V2))
      result mustBe HasSucceeded

      val fetch = await(termsOfUseInvitationRepository.fetch(applicationId))
      fetch mustBe Some(TermsOfUseInvitation(applicationId, now, now, now, None, TERMS_OF_USE_V2))
    }
  }
}
