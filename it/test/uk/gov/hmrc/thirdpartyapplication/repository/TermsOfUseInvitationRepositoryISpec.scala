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
import play.api.libs.json._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access.Standard
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{Collaborator, IpAllowlist, RateLimitTier}
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.models.TermsOfUseInvitationState._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util.{JavaDateTimeTestUtils, MetricsHelper}
import uk.gov.hmrc.utils.ServerBaseISpec

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import scala.concurrent.ExecutionContext.Implicits.global

object TermsOfUseInvitationRepositoryISpecExample extends FixedClock {
  val appId                = ApplicationId.random
  val termsOfUseInvitation = TermsOfUseInvitation(appId, instant, instant, instant.plus(1, ChronoUnit.DAYS), None, TermsOfUseInvitationState.TERMS_OF_USE_V2)

  val json = Json.obj(
    "applicationId" -> JsString(appId.toString()),
    "createdOn"     -> MongoJavatimeHelper.asJsValue(instant),
    "lastUpdated"   -> MongoJavatimeHelper.asJsValue(instant),
    "dueBy"         -> MongoJavatimeHelper.asJsValue(instant.plus(1, ChronoUnit.DAYS)),
    "status"        -> "TERMS_OF_USE_V2"
  )
}

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

  import TermsOfUseInvitationRepositoryISpecExample._

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
  private val applicationRepository: ApplicationRepository                   = app.injector.instanceOf[ApplicationRepository]

  protected override def beforeEach(): Unit = {
    super.beforeEach()
    await(termsOfUseInvitationRepository.collection.drop().toFuture())
    await(applicationRepository.collection.drop().toFuture())

    await(termsOfUseInvitationRepository.ensureIndexes())
    await(applicationRepository.ensureIndexes())
  }

  "mongo formats" should {
    import TermsOfUseInvitationRepository.MongoFormats.formatTermsOfUseInvitation

    "write to json" in {
      Json.toJson(termsOfUseInvitation) mustBe json
    }

    "read from json" in {
      Json.fromJson[TermsOfUseInvitation](json).get mustBe termsOfUseInvitation
    }
  }

  "mongo formatting in scope for repository" should {
    import org.mongodb.scala.Document
    import org.mongodb.scala.result.InsertOneResult

    def saveMongoJson(rawJson: JsObject): InsertOneResult = {
      await(mongoDatabase.getCollection("termsOfUseInvitation").insertOne(Document(rawJson.toString())).toFuture())
    }

    "read existing document from mongo" in {
      saveMongoJson(json)
      val result = await(termsOfUseInvitationRepository.fetch(appId))
      result.get mustBe termsOfUseInvitation
    }
  }

  "create" should {
    "create an entry" in {
      val applicationId = ApplicationId.random
      val touInvite     = TermsOfUseInvitation(applicationId, instant, instant, instant, None, EMAIL_SENT)

      val result = await(termsOfUseInvitationRepository.create(touInvite))

      result mustBe Some(touInvite)
      await(termsOfUseInvitationRepository.collection.countDocuments().toFuture().map(x => x.toInt)) mustBe 1
    }
  }

  "fetch" should {
    "fetch an entry" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val touInvite1     = TermsOfUseInvitation(applicationId1, instant, instant, instant, None, EMAIL_SENT)
      val touInvite2     = TermsOfUseInvitation(applicationId2, instant, instant, instant, None, EMAIL_SENT)

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
      val touInvite1     = TermsOfUseInvitation(applicationId1, instant, instant, instant, None, EMAIL_SENT)
      val touInvite2     = TermsOfUseInvitation(applicationId2, instant, instant, instant, None, TERMS_OF_USE_V2)

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
      val touInvite1     = TermsOfUseInvitation(applicationId1, instant, instant, instant, None, EMAIL_SENT)
      val touInvite2     = TermsOfUseInvitation(applicationId2, instant, instant, instant, None, TERMS_OF_USE_V2)

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
      val dueBy1         = startDate1.plus(21, ChronoUnit.DAYS)
      val startDate2     = Instant.parse("2023-06-14T12:02:04.000Z")
      val dueBy2         = startDate2.plus(21, ChronoUnit.DAYS)
      val startDate3     = Instant.parse("2023-06-28T12:03:06.000Z")
      val dueBy3         = startDate3.plus(21, ChronoUnit.DAYS)

      val touInvite1 = TermsOfUseInvitation(applicationId1, startDate1, startDate1, dueBy1, None, EMAIL_SENT)
      val touInvite2 = TermsOfUseInvitation(applicationId2, startDate2, startDate2, dueBy2, None, EMAIL_SENT)
      val touInvite3 = TermsOfUseInvitation(applicationId3, startDate3, startDate3, dueBy3, None, TERMS_OF_USE_V2)

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      val findDueBy = Instant.parse("2023-07-03T12:00:00.000Z")
      val result    = await(termsOfUseInvitationRepository.fetchByStatusBeforeDueBy(EMAIL_SENT, findDueBy))

      result.size mustBe 1
      result mustBe List(touInvite1)
    }
  }

  "fetchByStatusesBeforeDueBy" should {
    "fetch an entry" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val applicationId4 = ApplicationId.random
      val applicationId5 = ApplicationId.random
      val startDate1     = Instant.parse("2023-06-01T12:01:02.000Z")
      val dueBy1         = startDate1.plus(21, ChronoUnit.DAYS)
      val startDate2     = Instant.parse("2023-06-02T12:02:04.000Z")
      val dueBy2         = startDate2.plus(21, ChronoUnit.DAYS)
      val startDate3     = Instant.parse("2023-06-28T12:03:06.000Z")
      val dueBy3         = startDate3.plus(21, ChronoUnit.DAYS)

      val touInvite1 = TermsOfUseInvitation(applicationId1, startDate1, startDate1, dueBy1, None, EMAIL_SENT)
      val touInvite2 = TermsOfUseInvitation(applicationId2, startDate2, startDate2, dueBy2, None, REMINDER_EMAIL_SENT)
      val touInvite3 = TermsOfUseInvitation(applicationId3, startDate3, startDate3, dueBy3, None, REMINDER_EMAIL_SENT)
      val touInvite4 = TermsOfUseInvitation(applicationId4, startDate2, startDate2, dueBy2, None, FAILED)
      val touInvite5 = TermsOfUseInvitation(applicationId5, startDate1, startDate1, dueBy1, None, TERMS_OF_USE_V2)

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      await(termsOfUseInvitationRepository.create(touInvite4))
      await(termsOfUseInvitationRepository.create(touInvite5))
      val findDueBy = Instant.parse("2023-07-03T12:00:00.000Z")
      val result    = await(termsOfUseInvitationRepository.fetchByStatusesBeforeDueBy(findDueBy, EMAIL_SENT, REMINDER_EMAIL_SENT))

      result.size mustBe 2
      result mustBe List(touInvite1, touInvite2)
    }
  }

  "updateState" should {
    "update the status of an existing entry" in {
      val applicationId = ApplicationId.random
      val touInvite     = TermsOfUseInvitation(applicationId, instant, instant, instant, None, EMAIL_SENT)

      await(termsOfUseInvitationRepository.create(touInvite))
      val result = await(termsOfUseInvitationRepository.updateState(applicationId, TERMS_OF_USE_V2))
      result mustBe HasSucceeded

      val fetch = await(termsOfUseInvitationRepository.fetch(applicationId))
      fetch mustBe Some(TermsOfUseInvitation(applicationId, instant, instant, instant, None, TERMS_OF_USE_V2))
    }
  }

  "updateReminderSent" should {
    "update the status and reminder sent date of an existing entry" in {
      val applicationId = ApplicationId.random
      val touInvite     = TermsOfUseInvitation(applicationId, instant, instant, instant, None, EMAIL_SENT)

      await(termsOfUseInvitationRepository.create(touInvite))
      val result = await(termsOfUseInvitationRepository.updateReminderSent(applicationId))
      result mustBe HasSucceeded

      val fetch = await(termsOfUseInvitationRepository.fetch(applicationId))
      fetch mustBe Some(TermsOfUseInvitation(applicationId, instant, instant, instant, Some(instant), REMINDER_EMAIL_SENT))
    }
  }

  "updateResetBackToEmailSent" should {
    "update the status and due by date of an existing entry" in {
      val applicationId = ApplicationId.random
      val newDueByDate  = instant.plus(30, ChronoUnit.DAYS)
      val touInvite     = TermsOfUseInvitation(applicationId, instant, instant, instant, None, FAILED)

      await(termsOfUseInvitationRepository.create(touInvite))
      val result = await(termsOfUseInvitationRepository.updateResetBackToEmailSent(applicationId, newDueByDate))
      result mustBe HasSucceeded

      val fetch = await(termsOfUseInvitationRepository.fetch(applicationId))
      fetch mustBe Some(TermsOfUseInvitation(applicationId, instant, instant, newDueByDate, None, EMAIL_SENT))
    }
  }

  "delete" should {
    "delete an entry" in {
      val applicationId = ApplicationId.random
      val touInvite     = TermsOfUseInvitation(applicationId, instant, instant, instant, None, EMAIL_SENT)

      val result = await(termsOfUseInvitationRepository.create(touInvite))
      result mustBe Some(touInvite)

      val delete = await(termsOfUseInvitationRepository.delete(applicationId))
      delete mustBe HasSucceeded

      await(termsOfUseInvitationRepository.collection.countDocuments().toFuture().map(x => x.toInt)) mustBe 0
    }

    "not fail if no record found" in {
      val applicationId = ApplicationId.random

      val delete = await(termsOfUseInvitationRepository.delete(applicationId))
      delete mustBe HasSucceeded

      await(termsOfUseInvitationRepository.collection.countDocuments().toFuture().map(x => x.toInt)) mustBe 0
    }
  }

  "search" should {

    val applicationId1 = ApplicationId.random
    val applicationId2 = ApplicationId.random
    val applicationId3 = ApplicationId.random
    val applicationId4 = ApplicationId.random
    val applicationId5 = ApplicationId.random
    val startDate      = Instant.parse("2023-06-01T12:01:02.000Z")
    val dueBy          = startDate.plus(21, ChronoUnit.DAYS)

    val application1 = anApplicationData(applicationId1, "Pete app name 1")
    val application2 = anApplicationData(applicationId2, "Pete app name 2")
    val application3 = anApplicationData(applicationId3, "Bob app name")
    val application4 = anApplicationData(applicationId4, "Nicola app name")
    val application5 = anApplicationData(applicationId5, "Nicholas app name")

    val touInvite1 = TermsOfUseInvitation(applicationId1, startDate, startDate, dueBy, None, EMAIL_SENT)
    val touInvite2 = TermsOfUseInvitation(applicationId2, startDate, startDate, dueBy, None, REMINDER_EMAIL_SENT)
    val touInvite3 = TermsOfUseInvitation(applicationId3, startDate, startDate, dueBy, None, REMINDER_EMAIL_SENT)
    val touInvite4 = TermsOfUseInvitation(applicationId4, startDate, startDate, dueBy, None, FAILED)
    val touInvite5 = TermsOfUseInvitation(applicationId5, startDate, startDate, dueBy, None, TERMS_OF_USE_V2)

    val touInviteWithApp1 =
      TermsOfUseInvitationWithApplication(applicationId1, startDate, startDate, dueBy, None, EMAIL_SENT, Set(TermsOfUseApplication(application1.id, application1.name)))
    val touInviteWithApp2 =
      TermsOfUseInvitationWithApplication(applicationId2, startDate, startDate, dueBy, None, REMINDER_EMAIL_SENT, Set(TermsOfUseApplication(application2.id, application2.name)))
    val touInviteWithApp3 =
      TermsOfUseInvitationWithApplication(applicationId3, startDate, startDate, dueBy, None, REMINDER_EMAIL_SENT, Set(TermsOfUseApplication(application3.id, application3.name)))
    val touInviteWithApp4 =
      TermsOfUseInvitationWithApplication(applicationId4, startDate, startDate, dueBy, None, FAILED, Set(TermsOfUseApplication(application4.id, application4.name)))
    val touInviteWithApp5 =
      TermsOfUseInvitationWithApplication(applicationId5, startDate, startDate, dueBy, None, TERMS_OF_USE_V2, Set(TermsOfUseApplication(application5.id, application5.name)))

    "return expected result of 1 for email sent status search" in {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      await(termsOfUseInvitationRepository.create(touInvite4))
      await(termsOfUseInvitationRepository.create(touInvite5))

      val filters        = List(EmailSent)
      val searchCriteria = TermsOfUseSearch(filters)
      val result         = await(termsOfUseInvitationRepository.search(searchCriteria))

      result.size mustBe 1
      result mustBe List(touInviteWithApp1)
    }

    "return expected result of 3 for email sent & reminder email sent status search" in {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      await(termsOfUseInvitationRepository.create(touInvite4))
      await(termsOfUseInvitationRepository.create(touInvite5))

      val filters        = List(EmailSent, ReminderEmailSent)
      val searchCriteria = TermsOfUseSearch(filters)
      val result         = await(termsOfUseInvitationRepository.search(searchCriteria))

      result.size mustBe 3
      result mustBe List(touInviteWithApp1, touInviteWithApp2, touInviteWithApp3)
    }

    "return expected result of 5 for all status search" in {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      await(termsOfUseInvitationRepository.create(touInvite4))
      await(termsOfUseInvitationRepository.create(touInvite5))

      val filters        = List.empty
      val searchCriteria = TermsOfUseSearch(filters)
      val result         = await(termsOfUseInvitationRepository.search(searchCriteria))

      result.size mustBe 5
      result mustBe List(touInviteWithApp1, touInviteWithApp2, touInviteWithApp3, touInviteWithApp4, touInviteWithApp5)
    }

    "return expected result of 1 for exact name text search" in {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      await(termsOfUseInvitationRepository.create(touInvite4))
      await(termsOfUseInvitationRepository.create(touInvite5))

      val filters        = List(TermsOfUseTextSearch)
      val searchCriteria = TermsOfUseSearch(filters, Some("Pete app name 1"))
      val result         = await(termsOfUseInvitationRepository.search(searchCriteria))

      result.size mustBe 1
      result mustBe List(touInviteWithApp1)
    }

    "return expected result of 2 for partial name text search" in {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      await(termsOfUseInvitationRepository.create(touInvite4))
      await(termsOfUseInvitationRepository.create(touInvite5))

      val filters        = List(TermsOfUseTextSearch)
      val searchCriteria = TermsOfUseSearch(filters, Some("Pete app name"))
      val result         = await(termsOfUseInvitationRepository.search(searchCriteria))

      result.size mustBe 2
      result mustBe List(touInviteWithApp1, touInviteWithApp2)
    }

    "return expected result of 1 for partial name text AND status search" in {
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      await(termsOfUseInvitationRepository.create(touInvite1))
      await(termsOfUseInvitationRepository.create(touInvite2))
      await(termsOfUseInvitationRepository.create(touInvite3))
      await(termsOfUseInvitationRepository.create(touInvite4))
      await(termsOfUseInvitationRepository.create(touInvite5))

      val filters        = List(TermsOfUseTextSearch, Failed)
      val searchCriteria = TermsOfUseSearch(filters, Some("Nic"))
      val result         = await(termsOfUseInvitationRepository.search(searchCriteria))

      result.size mustBe 1
      result mustBe List(touInviteWithApp4)
    }
  }

  private def anApplicationData(id: ApplicationId, name: String): StoredApplication = {
    StoredApplication(
      id,
      name,
      name.toLowerCase(),
      Set(Collaborator(LaxEmailAddress("user@example.com"), Collaborator.Roles.ADMINISTRATOR, UserId.random)),
      Some("description"),
      "myapplication",
      ApplicationTokens(
        StoredToken(ClientId.random, "ccc")
      ),
      productionState("ted@example.com"),
      Standard(),
      instant,
      Some(instant),
      547,
      Some(RateLimitTier.BRONZE),
      Environment.PRODUCTION.toString(),
      None,
      false,
      IpAllowlist(),
      true
    )
  }
}
