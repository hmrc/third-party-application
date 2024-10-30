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

import java.time.{Clock, Duration}
import scala.util.Random.nextString

import org.mockito.MockitoSugar.{mock, times, verify, verifyNoMoreInteractions}
import org.mongodb.scala.model.{Filters, Updates}
import org.scalatest.BeforeAndAfterEach

import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.common.domain.models.FullName
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.models.{StandardAccess => _}
import uk.gov.hmrc.thirdpartyapplication.util._

object ApplicationRepositoryISpecExample extends ServerBaseISpec with FixedClock with CommonApplicationId with CollaboratorTestData {
  val clientId       = ClientId.random
  val clientSecretId = ClientSecret.Id.random
  val userId         = UserId.random
  val submissionId   = SubmissionId.random

  val aResponsibleIndividual = ResponsibleIndividual(FullName("bob"), LaxEmailAddress("bob@example.com"))

  val application = StoredApplication(
    applicationId,
    ApplicationName("AppName"),
    "appname",
    Set(Collaborators.Administrator(userId, LaxEmailAddress("bob@example.com"))),
    None,
    "wso2",
    ApplicationTokens(StoredToken(clientId, "accessABC", List(StoredClientSecret("a", instant, None, clientSecretId, "hashme")))),
    ApplicationState(State.TESTING, None, None, None, instant),
    Access.Standard(
      importantSubmissionData = Some(ImportantSubmissionData(
        organisationUrl = None,
        responsibleIndividual = aResponsibleIndividual,
        serverLocations = Set(ServerLocation.InUK),
        termsAndConditionsLocation = TermsAndConditionsLocations.InDesktopSoftware,
        privacyPolicyLocation = PrivacyPolicyLocations.NoneProvided,
        termsOfUseAcceptances = List(TermsOfUseAcceptance(aResponsibleIndividual, instant, submissionId))
      ))
    ),
    instant,
    None,
    GrantLength.EIGHTEEN_MONTHS.period,
    Some(RateLimitTier.BRONZE),
    Environment.PRODUCTION,
    Some(CheckInformation(
      Some(ContactDetails(FullName("Contact"), LaxEmailAddress("contact@example.com"), "123456789")),
      termsOfUseAgreements = List(
        TermsOfUseAgreement(LaxEmailAddress("bob@example.com"), instant, "1.0")
      )
    )),
    false,
    IpAllowlist()
  )

  def json(withInstance: Boolean) = Json.obj(
    "id"                        -> JsString(applicationId.toString()),
    "name"                      -> JsString("AppName"),
    "normalisedName"            -> JsString("appname"),
    "collaborators"             -> JsArray(Seq(Json.obj(
      "userId"       -> JsString(userId.toString()),
      "emailAddress" -> "bob@example.com",
      "role"         -> "ADMINISTRATOR"
    ))),
    "wso2ApplicationName"       -> JsString("wso2"),
    "tokens"                    -> Json.obj(
      "production" -> Json.obj(
        "clientId"      -> JsString(clientId.toString()),
        "accessToken"   -> JsString("accessABC"),
        "clientSecrets" -> JsArray(Seq(Json.obj(
          "name"         -> JsString("a"),
          "createdOn"    -> MongoJavatimeHelper.asJsValue(instant),
          "id"           -> JsString(clientSecretId.toString()),
          "hashedSecret" -> JsString("hashme")
        )))
      )
    ),
    "state"                     -> Json.obj(
      "name"      -> JsString("TESTING"),
      "updatedOn" -> MongoJavatimeHelper.asJsValue(instant)
    ),
    "access"                    -> Json.obj(
      "redirectUris"            -> JsArray(Seq()),
      "overrides"               -> JsArray(Seq()),
      "importantSubmissionData" -> Json.obj(
        "responsibleIndividual"      -> Json.obj(
          "fullName"     -> "bob",
          "emailAddress" -> "bob@example.com"
        ),
        "serverLocations"            -> JsArray(Seq(Json.obj("serverLocation" -> "inUK"))),
        "termsAndConditionsLocation" -> Json.obj("termsAndConditionsType" -> "inDesktop"),
        "privacyPolicyLocation"      -> Json.obj("privacyPolicyType" -> "noneProvided"),
        "termsOfUseAcceptances"      -> JsArray(Seq(
          if (withInstance) {
            Json.obj(
              "responsibleIndividual" -> Json.obj(
                "fullName"     -> "bob",
                "emailAddress" -> "bob@example.com"
              ),
              "dateTime"              -> MongoJavatimeHelper.asJsValue(instant),
              "submissionId"          -> JsString(submissionId.toString()),
              "submissionInstance"    -> JsNumber(0)
            )
          } else {
            Json.obj(
              "responsibleIndividual" -> Json.obj(
                "fullName"     -> "bob",
                "emailAddress" -> "bob@example.com"
              ),
              "dateTime"              -> MongoJavatimeHelper.asJsValue(instant),
              "submissionId"          -> JsString(submissionId.toString())
            )
          }
        ))
      ),
      "accessType"              -> JsString("STANDARD")
    ),
    "createdOn"                 -> MongoJavatimeHelper.asJsValue(instant),
    "refreshTokensAvailableFor" -> GrantLength.EIGHTEEN_MONTHS.period,
    "rateLimitTier"             -> JsString("BRONZE"),
    "environment"               -> JsString("PRODUCTION"),
    "checkInformation"          -> Json.obj(
      "contactDetails"                         -> Json.obj(
        "fullname"        -> JsString("Contact"),
        "email"           -> JsString("contact@example.com"),
        "telephoneNumber" -> JsString("123456789")
      ),
      "confirmedName"                          -> JsFalse,
      "apiSubscriptionsConfirmed"              -> JsFalse,
      "apiSubscriptionConfigurationsConfirmed" -> JsFalse,
      "providedPrivacyPolicyURL"               -> JsFalse,
      "providedTermsAndConditionsURL"          -> JsFalse,
      "teamConfirmed"                          -> JsFalse,
      "termsOfUseAgreements"                   -> JsArray(Seq(Json.obj(
        "emailAddress" -> JsString("bob@example.com"),
        "timeStamp"    -> MongoJavatimeHelper.asJsValue(instant),
        "version"      -> JsString("1.0")
      )))
    ),
    "blocked"                   -> JsFalse,
    "ipAllowlist"               -> Json.obj("required" -> JsFalse, "allowlist" -> JsArray.empty),
    "allowAutoDelete"           -> JsTrue
  )
}

class ApplicationRepositoryISpec
    extends ServerBaseISpec
    with CleanMongoCollectionSupport
    with SubmissionsTestData
    with StoredApplicationFixtures
    with JavaDateTimeTestUtils
    with BeforeAndAfterEach
    with MetricsHelper
    with FixedClock
    with ApplicationRepositoryTestData {

  val adminName = "Admin Example"

  import ApplicationRepositoryISpecExample._

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private val applicationRepository =
    app.injector.instanceOf[ApplicationRepository]

  private val subscriptionRepository =
    app.injector.instanceOf[SubscriptionRepository]

  private val stateHistoryRepository =
    app.injector.instanceOf[StateHistoryRepository]

  private val notificationRepository =
    app.injector.instanceOf[NotificationRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(applicationRepository.collection.drop().toFuture())
    await(subscriptionRepository.collection.drop().toFuture())
    await(notificationRepository.collection.drop().toFuture())

    await(applicationRepository.ensureIndexes())
    await(subscriptionRepository.ensureIndexes())
    await(notificationRepository.ensureIndexes())
  }

  private def generateClientId = ClientId.random

  private def generateAccessToken = {
    val lengthOfRandomToken = 5
    nextString(lengthOfRandomToken)
  }

  "mongo formats" should {
    import ApplicationRepository.MongoFormats.formatStoredApplication

    "write to json" in {
      Json.toJson(application) mustBe json(true)
    }

    "read from json" in {
      Json.fromJson[StoredApplication](json(false)) mustBe JsSuccess(application)
    }
  }

  "mongo formatting in scope for repository" should {
    import org.mongodb.scala.Document
    import org.mongodb.scala.result.InsertOneResult

    def saveMongoJson(rawJson: JsObject): InsertOneResult = {
      await(mongoDatabase.getCollection("application").insertOne(Document(rawJson.toString())).toFuture())
    }

    "read existing document from mongo" in {
      saveMongoJson(json(true))
      val result = await(applicationRepository.fetch(applicationId))
      result.get mustBe application
    }
  }

  "save" should {

    "create an application and retrieve it from database" in {
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get

      retrieved mustBe application
      retrieved.allowAutoDelete mustBe true
    }

    "update an application" in {
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get
      retrieved mustBe application

      val updated = retrieved.copy(name = ApplicationName("new name"))
      await(applicationRepository.save(updated))

      val newRetrieved = await(applicationRepository.fetch(application.id)).get
      newRetrieved mustBe updated
    }
  }

  "updateAllowAutoDelete" should {

    "set the allowAutoDelete field on an Application document to false" in {
      val savedApplication = await(
        applicationRepository.save(
          anApplicationDataForTest(applicationId)
        )
      )

      savedApplication.allowAutoDelete mustBe true

      val updatedApplication = await(
        applicationRepository.updateAllowAutoDelete(
          applicationId,
          false
        )
      )

      updatedApplication.allowAutoDelete mustBe false
    }
  }

  "updateApplicationRateLimit" should {

    "set the rateLimitTier field on an Application document" in {
      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            ClientId("aaa")
          )
            .withState(appStateProduction)
            .copy(
              rateLimitTier = Some(RateLimitTier.BRONZE),
              lastAccess = Some(instant)
            )
        )
      )

      val updatedRateLimit   = RateLimitTier.GOLD
      val updatedApplication = await(
        applicationRepository.updateApplicationRateLimit(
          applicationId,
          updatedRateLimit
        )
      )

      updatedApplication.rateLimitTier mustBe Some(updatedRateLimit)
    }

    "set the grant Length field on an Application document" in {
      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            ClientId("aaa")
          )
            .withState(appStateProduction)
            .copy(refreshTokensAvailableFor = newGrantLength)
        )
      )

      val newRetrieved = await(applicationRepository.fetch(applicationId)).get

      newRetrieved.refreshTokensAvailableFor mustBe newGrantLength
    }

    "set the rateLimitTier field on an Application document where none previously existed" in {
      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            ClientId("aaa")
          )
            .withState(appStateProduction)
            .copy(rateLimitTier = None)
        )
      )

      val updatedRateLimit   = RateLimitTier.GOLD
      val updatedApplication = await(
        applicationRepository.updateApplicationRateLimit(
          applicationId,
          updatedRateLimit
        )
      )

      updatedApplication.rateLimitTier mustBe Some(updatedRateLimit)
    }
  }

  "updateApplicationIpAllowlist" should {
    "set the ipAllowlist fields on an Application document" in {
      await(applicationRepository.save(anApplicationDataForTest(applicationId)))

      val updatedIpAllowlist = IpAllowlist(
        required = true,
        Set("192.168.100.0/22", "192.168.104.1/32")
      )
      val updatedApplication = await(
        applicationRepository.updateApplicationIpAllowlist(
          applicationId,
          updatedIpAllowlist
        )
      )

      updatedApplication.ipAllowlist mustBe updatedIpAllowlist
    }
  }

  "updateApplicationGrantLength" should {
    "set the grantLength fields on an Application document" in {
      await(applicationRepository.save(anApplicationDataForTest(applicationId)))

      val updatedApplication = await(
        applicationRepository.updateApplicationGrantLength(
          applicationId,
          newGrantLength
        )
      )

      updatedApplication.refreshTokensAvailableFor mustBe newGrantLength
    }
  }

  "updateRedirectUris" should {
    "set the redirectUris on an Application document" in {
      await(applicationRepository.save(anApplicationDataForTest(applicationId)))

      val updateRedirectUris = List("https://new-url.example.com", "https://new-url.example.com/other-redirect").map(RedirectUri.unsafeApply(_))
      val updatedApplication = await(
        applicationRepository.updateRedirectUris(
          applicationId,
          updateRedirectUris
        )
      )

      updatedApplication.access match {
        case access: Access.Standard => access.redirectUris mustBe updateRedirectUris
        case _                       => fail("Wrong access - expecting standard")
      }
    }
  }

  "recordApplicationUsage" should {

    "update the lastAccess property" in {
      val clientId = ClientId("aaa")

      val application =
        anApplicationDataForTest(
          applicationId,
          clientId
        )
          .withState(appStateProduction)
          .copy(lastAccess = Some(instant.minus(Duration.ofDays(20)))) // scalastyle:ignore magic.number

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.findAndRecordApplicationUsage(clientId)).get

      timestampShouldBeApproximatelyNow(retrieved.lastAccess.get, clock = clock)
    }

    "update the grantLength property" in {
      val clientId    = ClientId("aaa")
      val application =
        anApplicationDataForTest(
          applicationId,
          clientId
        )
          .withState(appStateProduction)
          .copy(refreshTokensAvailableFor = newGrantLength)
      // scalastyle:ignore magic.number

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.findAndRecordApplicationUsage(clientId)).get

      retrieved.refreshTokensAvailableFor mustBe newGrantLength
    }
  }

  "recordServerTokenUsage" should {
    "update the lastAccess and lastAccessTokenUsage properties" in {
      val application =
        anApplicationDataForTest(
          applicationId
          // ClientId("aaa"),
        ).withState(appStateProduction)
          .copy(lastAccess = Some(instant.minus(Duration.ofDays(20)))) // scalastyle:ignore magic.number

      application.tokens.production.lastAccessTokenUsage mustBe None

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.findAndRecordServerTokenUsage(application.tokens.production.accessToken)).get

      timestampShouldBeApproximatelyNow(retrieved.lastAccess.get, clock = clock)
      timestampShouldBeApproximatelyNow(
        retrieved.tokens.production.lastAccessTokenUsage.get,
        clock = clock
      )
    }
  }

  "recordClientSecretUsage" should {
    "create a lastAccess property for client secret if it does not already exist" in {
      val application = anApplicationDataForTest(
        applicationId,
        ClientId("aaa")
      ).withState(appStateProduction)

      val generatedClientSecretId =
        application.tokens.production.clientSecrets.head.id

      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.recordClientSecretUsage(
          applicationId,
          generatedClientSecretId
        )
      )

      application.tokens.production.clientSecrets.head.lastAccess mustBe None // Original object has no value
      timestampShouldBeApproximatelyNow(
        retrieved.tokens.production.clientSecrets.head.lastAccess.get,
        clock = clock
      )
    }

    "update an existing lastAccess property for a client secret" in {
      val applicationTokens = ApplicationTokens(
        StoredToken(
          ClientId("aaa"),
          generateAccessToken,
          List(
            aClientSecret(
              name = "Default",
              lastAccess = Some(instant.minus(Duration.ofDays(20))),
              hashedSecret = "hashed-secret"
            )
          )
        )
      )

      val application             = anApplicationDataForTest(
        applicationId,
        ClientId("aaa")
      )
        .withState(appStateProduction)
        .copy(tokens = applicationTokens)
      val generatedClientSecretId =
        application.tokens.production.clientSecrets.head.id

      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.recordClientSecretUsage(
          applicationId,
          generatedClientSecretId
        )
      )

      timestampShouldBeApproximatelyNow(
        retrieved.tokens.production.clientSecrets.head.lastAccess.get,
        clock = clock
      )
      retrieved.tokens.production.clientSecrets.head.lastAccess.get.isAfter(
        applicationTokens.production.clientSecrets.head.lastAccess.get
      ) mustBe true
    }

    "update the correct client secret when there are multiple" in {
      val testStartTime     = instant
      val secretToUpdate    =
        aClientSecret(
          name = "SecretToUpdate",
          lastAccess = Some(instant.minus(Duration.ofDays(20))),
          hashedSecret = "hashed-secret"
        )
      val applicationTokens =
        ApplicationTokens(
          StoredToken(
            ClientId("aaa"),
            generateAccessToken,
            List(
              secretToUpdate,
              aClientSecret(
                name = "SecretToLeave",
                lastAccess = Some(instant.minus(Duration.ofDays(20))),
                hashedSecret = "hashed-secret"
              )
            )
          )
        )
      val application       = anApplicationDataForTest(
        applicationId,
        ClientId("aaa")
      )
        .withState(appStateProduction)
        .copy(tokens = applicationTokens)

      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.recordClientSecretUsage(
          applicationId,
          secretToUpdate.id
        )
      )

      retrieved.tokens.production.clientSecrets.foreach(retrievedClientSecret =>
        if (retrievedClientSecret.id == secretToUpdate.id)
          timestampShouldBeApproximatelyNow(
            retrievedClientSecret.lastAccess.get,
            clock = clock
          )
        else
          retrievedClientSecret.lastAccess.get.isBefore(
            testStartTime
          ) mustBe true
      )
    }
  }

  "fetchByClientId" should {

    "retrieve the application for a given client id when it has a matching client id" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa")
      )
        .withState(appStateProduction)

      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("zzz")
      )
        .withState(appStateProduction)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(
        applicationRepository.fetchByClientId(
          application2.tokens.production.clientId
        )
      )

      retrieved mustBe Some(application2)
    }

    "retrieve the grant length for an application for a given client id when it has a matching client id" in {
      val grantLength1 = GrantLength.ONE_MONTH.period
      val grantLength2 = GrantLength.ONE_YEAR.period
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa")
      )
        .withState(appStateProduction)
        .copy(refreshTokensAvailableFor = grantLength1)

      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("zzz")
      )
        .withState(appStateProduction)
        .copy(refreshTokensAvailableFor = grantLength2)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved1 = await(
        applicationRepository.fetchByClientId(
          application1.tokens.production.clientId
        )
      )
      val retrieved2 = await(
        applicationRepository.fetchByClientId(
          application2.tokens.production.clientId
        )
      )

      retrieved1.map(_.refreshTokensAvailableFor) mustBe Some(grantLength1)
      retrieved2.map(_.refreshTokensAvailableFor) mustBe Some(grantLength2)
    }

    "do not retrieve the application for a given client id when it has a matching client id but is deleted" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa")
      )
        .withState(appStateDeleted)

      await(applicationRepository.save(application1))

      val retrieved = await(
        applicationRepository.fetchByClientId(
          application1.tokens.production.clientId
        )
      )

      retrieved mustBe None
    }
  }

  "fetchByServerToken" should {

    "retrieve the application when it is matched for access token" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa")
      )
        .withState(appStateProduction)

      val application2 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("zzz")
      )
        .withState(appStateProduction)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val retrieved = await(
        applicationRepository.fetchByServerToken(
          application2.tokens.production.accessToken
        )
      )

      retrieved mustBe Some(application2)
    }

    "do not retrieve the application when it is matched for access token but is deleted" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random,
        ClientId("aaa")
      )
        .withState(appStateDeleted)

      await(applicationRepository.save(application1))

      val retrieved = await(
        applicationRepository.fetchByServerToken(
          application1.tokens.production.accessToken
        )
      )

      retrieved mustBe None
    }
  }

  "fetchAllForEmailAddress" should {
    "retrieve all the applications for a given collaborator email address" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .withState(appStateDeleted)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      val retrieved =
        await(applicationRepository.fetchAllForEmailAddress("user@example.com"))

      retrieved mustBe List(application1, application2)
    }
  }

  "fetchStandardNonTestingApps" should {
    "retrieve all the standard applications not in TESTING (or DELETED) state" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .withState(appStatePendingRequesterVerification)

      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .withState(appStateProduction)

      val application4 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .withState(appStatePendingRequesterVerification)

      val application5 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .withState(appStateDeleted)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(applicationRepository.save(application4))
      await(applicationRepository.save(application5))

      val retrieved = await(applicationRepository.fetchStandardNonTestingApps())

      retrieved.toSet mustBe Set(application2, application3, application4)
    }

    "return empty list when no apps are found" in {
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "not return Access.Privileged applications" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random
      )
        .withState(appStateProduction)
        .withAccess(Access.Privileged())

      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "not return ROPC applications" in {
      val application1 = anApplicationDataForTest(
        ApplicationId.random
      )
        .withState(appStateProduction)
        .withAccess(Access.Ropc())

      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "return empty list when all apps in TESTING state" in {
      val application1 = anApplicationDataForTest(ApplicationId.random)

      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }

    "return empty list when all apps in DELETED state" in {
      val application1 = anApplicationDataForTest(ApplicationId.random).withState(appStateDeleted)

      await(applicationRepository.save(application1))
      await(applicationRepository.fetchStandardNonTestingApps()) mustBe Nil
    }
  }

  "fetchNonTestingApplicationByName" should {

    "retrieve the application with the matching name" in {
      val applicationName           = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.fetchApplicationsByName(applicationName))

      retrieved mustBe List(application)
    }

    "dont retrieve the application if it's a non-matching name" in {
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .copy(normalisedName = applicationNormalisedName)
      await(applicationRepository.save(application))

      val retrieved = await(
        applicationRepository.fetchApplicationsByName("non-matching-name")
      )

      retrieved mustBe List.empty
    }

    "dont retrieve the application with the matching name if its deleted" in {
      val applicationName           = "appName"
      val applicationNormalisedName = "appname"

      val application = anApplicationDataForTest(id = ApplicationId.random)
        .withState(appStateDeleted)
        .copy(normalisedName = applicationNormalisedName)

      await(applicationRepository.save(application))
      val retrieved =
        await(applicationRepository.fetchApplicationsByName(applicationName))

      retrieved mustBe List.empty
    }
  }

  "fetchAllByStatusDetails" should {

    val dayOfExpiry          = instant
    val expiryOnTheDayBefore = dayOfExpiry.minus(Duration.ofDays(1))
    val expiryOnTheDayAfter  = dayOfExpiry.plus(Duration.ofDays(1))

    def verifyApplications(
        responseApplications: Seq[StoredApplication],
        expectedState: State,
        expectedNumber: Int
      ): Unit = {
      responseApplications.foreach(app => app.state.name mustBe expectedState)
      withClue(
        s"The expected number of applications with state $expectedState is $expectedNumber"
      ) {
        responseApplications.size mustBe expectedNumber
      }
    }

    "retrieve the only application with PENDING_REQUESTER_VERIFICATION state that have been updated before the expiryDay" in {
      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, expiryOnTheDayBefore),
        createAppWithStatusUpdatedOn(State.PENDING_GATEKEEPER_APPROVAL, expiryOnTheDayBefore),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, expiryOnTheDayBefore),
        createAppWithStatusUpdatedOn(State.PRODUCTION, expiryOnTheDayBefore)
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )

      val applicationDetails = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetails,
        State.PENDING_REQUESTER_VERIFICATION,
        1
      )
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated before the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(
        State.PENDING_REQUESTER_VERIFICATION,
        expiryOnTheDayBefore
      )
      await(applicationRepository.save(application))

      val applicationDetails = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetails,
        State.PENDING_REQUESTER_VERIFICATION,
        1
      )
    }

    "retrieve the application with PENDING_REQUESTER_VERIFICATION state that have been updated on the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(
        State.PENDING_REQUESTER_VERIFICATION,
        dayOfExpiry
      )
      await(applicationRepository.save(application))

      val applicationDetails = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetails,
        State.PENDING_REQUESTER_VERIFICATION,
        1
      )
    }

    "retrieve no application with PENDING_REQUESTER_VERIFICATION state that have been updated after the dayOfExpiry" in {
      val application = createAppWithStatusUpdatedOn(
        State.PENDING_REQUESTER_VERIFICATION,
        expiryOnTheDayAfter
      )
      await(applicationRepository.save(application))

      val applicationDetail = await(
        applicationRepository.fetchAllByStatusDetails(
          State.PENDING_REQUESTER_VERIFICATION,
          dayOfExpiry
        )
      )

      verifyApplications(
        applicationDetail,
        State.PENDING_REQUESTER_VERIFICATION,
        0
      )
    }
  }

  "fetchByStatusDetailsAndEnvironment" should {
    val currentDate        = instant
    val yesterday          = currentDate.minus(Duration.ofDays(1))
    val dayBeforeYesterday = currentDate.minus(Duration.ofDays(2))
    val lastWeek           = currentDate.minus(Duration.ofDays(7))

    def verifyApplications(
        responseApplications: Seq[StoredApplication],
        expectedState: State,
        expectedNumber: Int
      ): Unit = {
      responseApplications.foreach(app => app.state.name mustBe expectedState)
      withClue(
        s"The expected number of applications with state $expectedState is $expectedNumber"
      ) {
        responseApplications.size mustBe expectedNumber
      }
    }

    "retrieve the only application with TESTING state that have been updated before the expiryDay" in {
      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, currentDate),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, lastWeek).copy(allowAutoDelete = false),
        createAppWithStatusUpdatedOn(State.TESTING, lastWeek)
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )

      val applicationDetails = await(
        applicationRepository.fetchByStatusDetailsAndEnvironmentForDeleteJob(
          State.TESTING,
          yesterday,
          Environment.PRODUCTION
        )
      )

      verifyApplications(
        applicationDetails,
        State.TESTING,
        2
      )
    }
  }

  "fetchByStatusDetailsAndEnvironmentNotAleadyNotified" should {

    val currentDate        = instant
    val yesterday          = currentDate.minus(Duration.ofDays(1))
    val dayBeforeYesterday = currentDate.minus(Duration.ofDays(2))
    val lastWeek           = currentDate.minus(Duration.ofDays(7))

    def verifyApplications(
        responseApplications: Seq[StoredApplication],
        expectedState: State,
        expectedNumber: Int
      ): Unit = {
      responseApplications.foreach(app => app.state.name mustBe expectedState)
      withClue(
        s"The expected number of applications with state $expectedState is $expectedNumber"
      ) {
        responseApplications.size mustBe expectedNumber
      }
    }

    "retrieve the only application with TESTING state that have been updated before the expiryDay" in {
      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, currentDate),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday).copy(allowAutoDelete = false),
        createAppWithStatusUpdatedOn(State.TESTING, lastWeek)
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )

      val applicationDetails = await(
        applicationRepository.fetchByStatusDetailsAndEnvironmentNotAleadyNotifiedForDeleteJob(
          State.TESTING,
          yesterday,
          Environment.PRODUCTION
        )
      )

      verifyApplications(
        applicationDetails,
        State.TESTING,
        2
      )
    }

    "retrieve the only application with TESTING state that have been updated before the expiryDay and don't return already notified ones" in {
      val app4 = createAppWithStatusUpdatedOn(State.TESTING, lastWeek)

      val applications = Seq(
        createAppWithStatusUpdatedOn(State.TESTING, currentDate),
        createAppWithStatusUpdatedOn(State.PENDING_REQUESTER_VERIFICATION, dayBeforeYesterday),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday).copy(allowAutoDelete = false),
        createAppWithStatusUpdatedOn(State.TESTING, dayBeforeYesterday),
        app4
      )
      applications.foreach(application =>
        await(applicationRepository.save(application))
      )
      await(notificationRepository.createEntity(Notification(app4.id, lastWeek, NotificationType.PRODUCTION_CREDENTIALS_REQUEST_EXPIRY_WARNING, NotificationStatus.SENT)))

      val applicationDetails = await(
        applicationRepository.fetchByStatusDetailsAndEnvironmentNotAleadyNotifiedForDeleteJob(
          State.TESTING,
          yesterday,
          Environment.PRODUCTION
        )
      )

      verifyApplications(
        applicationDetails,
        State.TESTING,
        1
      )
    }
  }

  "fetchVerifiableBy" should {

    "retrieve the application with verificationCode when in pendingRequesterVerification state" in {
      val application = anApplicationDataForTest(
        ApplicationId.random
      )
        .withState(appStatePendingRequesterVerification)

      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(appStateVerificationCode))
      retrieved mustBe Some(application)
    }

    "retrieve the application with verificationCode when in pre production state" in {
      val application = anApplicationDataForTest(
        ApplicationId.random
      )
        .withState(appStatePreProduction)

      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy(appStateVerificationCode))
      retrieved mustBe Some(application)
    }

    "not retrieve the application with an unknown verificationCode" in {
      val application = anApplicationDataForTest(
        ApplicationId.random
      )
        .withState(appStatePendingRequesterVerification)

      await(applicationRepository.save(application))
      val retrieved = await(applicationRepository.fetchVerifiableUpliftBy("aDifferentVerificationCode"))
      retrieved mustBe None
    }

    "not retrieve the application with verificationCode when in deleted state" in {
      val application = anApplicationDataForTest(
        ApplicationId.random
      )
        .withState(appStatePendingRequesterVerification)

      await(applicationRepository.save(application))
      await(applicationRepository.delete(application.id, instant))

      val retrieved = await(
        applicationRepository.fetchVerifiableUpliftBy(appStateVerificationCode)
      )
      retrieved mustBe None
    }
  }

  "AppsWithSubscriptions" should {
    "return Apps with their subscriptions" in {
      val api1        = "api-1"
      val api2        = "api-2"
      val api3        = "api-3"
      val api1Version = "api-1-version-1"
      val api2Version = "api-2-version-2"
      val api3Version = "api-3-version-3"

      val application1 = anApplicationDataForTest(id = ApplicationId.random, prodClientId = ClientId("aaa"))
      val application2 = anApplicationDataForTest(id = ApplicationId.random, prodClientId = ClientId("aab"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api1, api1Version, application1.id, application2.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api2, api2Version, application1.id, application2.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api3, api3Version, application2.id))
          .toFuture()
      )

      val expectedResult = List(
        GatekeeperAppSubsResponse(
          application1.id,
          application1.name,
          application1.lastAccess,
          Set(ApiIdentifier(ApiContext(api1), ApiVersionNbr(api1Version)), ApiIdentifier(ApiContext(api2), ApiVersionNbr(api2Version)))
        ),
        GatekeeperAppSubsResponse(
          application2.id,
          application2.name,
          application2.lastAccess,
          Set(
            ApiIdentifier(ApiContext(api1), ApiVersionNbr(api1Version)),
            ApiIdentifier(ApiContext(api2), ApiVersionNbr(api2Version)),
            ApiIdentifier(ApiContext(api3), ApiVersionNbr(api3Version))
          )
        )
      )

      val result = await(applicationRepository.getAppsWithSubscriptions)

      result must contain theSameElementsAs expectedResult
    }
  }

  "delete" should {

    "change an application's state to Deleted" in {
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get
      retrieved mustBe application

      await(applicationRepository.delete(application.id, instant))
      val result = await(applicationRepository.fetch(application.id))

      result.isDefined mustBe true
      result.get.state.name mustBe State.DELETED
    }
  }

  "hardDelete" should {

    "delete an application from the database" in {
      val application = anApplicationDataForTest(ApplicationId.random)
      await(applicationRepository.save(application))

      val retrieved = await(applicationRepository.fetch(application.id)).get
      retrieved mustBe application

      await(applicationRepository.hardDelete(application.id))
      val result = await(applicationRepository.fetch(application.id))

      result mustBe None
    }
  }

  "fetch" should {

    // API-3862: The wso2Username and wso2Password fields have been removed from StoredApplication, but will still exist in Mongo for most applications
    // Test that documents are still correctly deserialised into StoredApplication objects
    "retrieve an application when wso2Username and wso2Password exist" in {
      val application = anApplicationDataForTest(applicationId)

      await(applicationRepository.save(application))
      await(
        applicationRepository.collection
          .findOneAndUpdate(
            Filters.equal("id", Codecs.toBson(applicationId)),
            Updates.set("wso2Username", "legacyUsername")
          )
          .toFuture()
      )
      await(
        applicationRepository.collection
          .findOneAndUpdate(
            Filters.equal("id", Codecs.toBson(applicationId)),
            Updates.set("wso2Password", "legacyPassword")
          )
          .toFuture()
      )

      val result = await(applicationRepository.fetch(applicationId))

      result must not be None
    }
  }

  "fetchAllWithNoSubscriptions" should {
    "fetch only those applications with no subscriptions" in { // Needs revisiting

      val application1     = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2     = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val subscriptionData =
        aSubscriptionData("context", "version", application1.id)

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(
        subscriptionRepository.collection.insertOne(subscriptionData).toFuture()
      )

      val result = await(applicationRepository.fetchAllWithNoSubscriptions())

      result mustBe List(application2)
    }
  }

  "fetchAll" should {

    "fetch all existing applications" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(applicationRepository.fetchAll()) mustBe List(
        application1,
        application2
      )
    }
  }

  "fetchAllForContext" should {

    "fetch only those applications when the context matches" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-1", application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-2", application2.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("other", "version-2", application3.id))
          .toFuture()
      )

      val result =
        await(applicationRepository.fetchAllForContext("context".asContext))

      result mustBe List(application1, application2)
    }
  }

  "fetchAllForApiIdentifier" should {

    "fetch only those applications when the context and version matches" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-1", application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-2", application2.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "other",
              "version-2",
              application2.id,
              application3.id
            )
          )
          .toFuture()
      )

      val result = await(
        applicationRepository.fetchAllForApiIdentifier(
          "context".asIdentifier("version-2")
        )
      )

      result mustBe List(application2)
    }

    "fetch multiple applications with the same matching context and versions" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application3 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData("context", "version-1", application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "context",
              "version-2",
              application2.id,
              application3.id
            )
          )
          .toFuture()
      )

      val result = await(
        applicationRepository.fetchAllForApiIdentifier(
          "context".asIdentifier("version-2")
        )
      )

      result mustBe List(application2, application3)
    }

    "fetch no applications when the context and version do not match" in {
      val application1                            = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2                            = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val nonExistentApiIdentifier: ApiIdentifier =
        "other".asIdentifier("version-1")

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData("context-1", "version-1", application1.id)
          )
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData("context-2", "version-2", application2.id)
          )
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(
            aSubscriptionData(
              "other",
              "version-2",
              application1.id,
              application2.id
            )
          )
          .toFuture()
      )

      val result = await(
        applicationRepository.fetchAllForApiIdentifier(nonExistentApiIdentifier)
      )

      result mustBe List.empty
    }
  }

  "processAll" should {
    class TestService {
      def doSomething(application: StoredApplication): StoredApplication =
        application
    }

    "ensure function is called for every Application in collection" in {
      val firstApplication  = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val secondApplication = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(firstApplication))
      await(applicationRepository.save(secondApplication))

      val mockTestService = mock[TestService]

      await(
        applicationRepository.processAll(a => mockTestService.doSomething(a))
      )

      verify(mockTestService, times(1)).doSomething(firstApplication)
      verify(mockTestService, times(1)).doSomething(secondApplication)
      verifyNoMoreInteractions(mockTestService)
    }
  }

  "ApplicationWithSubscriptionCount" should {
    "return Applications with a count of subscriptions" in {
      val api1        = "api-1"
      val api2        = "api-2"
      val api3        = "api-3"
      val api1Version = "api-1-version-1"
      val api2Version = "api-2-version-2"
      val api3Version = "api-3-version-3"

      val application1 = aNamedApplicationData(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      .withName(ApplicationName("organisations/trusts"))

      val application2 = aNamedApplicationData(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      .withName(ApplicationName("application.com"))

      val application3 = aNamedApplicationData(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      .withName(ApplicationName("Get) Vat Done (Fast)"))

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))
      await(applicationRepository.save(application3))

      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api1, api1Version, application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api2, api2Version, application1.id))
          .toFuture()
      )
      await(
        subscriptionRepository.collection
          .insertOne(aSubscriptionData(api3, api3Version, application2.id))
          .toFuture()
      )

      val sanitisedApp1Name = sanitiseGrafanaNodeName(application1.name.value)
      val sanitisedApp2Name = sanitiseGrafanaNodeName(application2.name.value)
      val sanitisedApp3Name = sanitiseGrafanaNodeName(application3.name.value)

      val result =
        await(applicationRepository.getApplicationWithSubscriptionCount())

      result.get(
        s"applicationsWithSubscriptionCountV1.${sanitisedApp1Name}"
      ) mustBe Some(2)
      result.get(
        s"applicationsWithSubscriptionCountV1.${sanitisedApp2Name}"
      ) mustBe Some(1)
      result.get(
        s"applicationsWithSubscriptionCountV1.${sanitisedApp3Name}"
      ) mustBe None
    }

    "return Applications when more than 100 results bug" in {
      (1 to 200).foreach(i => {
        val api        = s"api-$i"
        val apiVersion = s"api-$i-version-$i"

        val application = anApplicationDataForTest(
          id = ApplicationId.random,
          prodClientId = generateClientId
        )
        await(applicationRepository.save(application))

        await(
          subscriptionRepository.collection
            .insertOne(aSubscriptionData(api, apiVersion, application.id))
            .toFuture()
        )
      })

      val result =
        await(applicationRepository.getApplicationWithSubscriptionCount())

      result.keys.count(_ => true) mustBe 200
    }
  }

  "addClientSecret" should {
    "append client secrets to an existing application" in {

      val savedApplication = await(
        applicationRepository.save(anApplicationDataForTest(applicationId))
      )

      val clientSecret       =
        aClientSecret(name = "secret-name", hashedSecret = "hashed-secret")
      val updatedApplication = await(
        applicationRepository.addClientSecret(applicationId, clientSecret)
      )

      savedApplication.tokens.production.clientSecrets must not contain clientSecret
      updatedApplication.tokens.production.clientSecrets must contain(
        clientSecret
      )
    }
  }

  "updateClientSecretName" should {
    def clientSecretWithId(
        application: StoredApplication,
        clientSecretId: ClientSecret.Id
      ): StoredClientSecret =
      application.tokens.production.clientSecrets
        .find(_.id == clientSecretId)
        .get
    def otherClientSecrets(
        application: StoredApplication,
        clientSecretId: ClientSecret.Id
      ): Seq[StoredClientSecret] =
      application.tokens.production.clientSecrets
        .filterNot(_.id == clientSecretId)

    "populate the name where it was an empty String" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = ClientSecret.Id.random

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(aClientSecret(clientSecretId))
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
    }

    "populate the name where it was Default" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = ClientSecret.Id.random

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(aClientSecret(clientSecretId, name = "Default"))
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
    }

    "populate the name where it was a masked String" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = ClientSecret.Id.random

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(
              aClientSecret(
                clientSecretId,
                name = "••••••••••••••••••••••••••••••••abc1"
              )
            )
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
    }

    "update correct client secret where there are multiple" in {
      val applicationId  = ApplicationId.random
      val clientSecretId = ClientSecret.Id.random

      val clientSecret1 = aClientSecret(name = "secret-that-should-not-change")
      val clientSecret2 = aClientSecret(name = "secret-that-should-not-change")
      val clientSecret3 = aClientSecret(clientSecretId, name = "secret-3")

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(clientSecret1, clientSecret2, clientSecret3)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretName(
          applicationId,
          clientSecretId,
          "new-name"
        )
      )

      clientSecretWithId(
        updatedApplication,
        clientSecretId
      ).name mustBe ("new-name")
      otherClientSecrets(updatedApplication, clientSecretId) foreach {
        otherSecret =>
          otherSecret.name mustBe ("secret-that-should-not-change")
      }
    }
  }

  "addApplicationTermsOfUseAcceptance" should {
    "update the application correctly" in {
      val responsibleIndividual   = ResponsibleIndividual(
        FullName("bob"),
        LaxEmailAddress("bob@example.com")
      )
      val acceptanceDate          = instant
      val submissionId            = SubmissionId.random
      val acceptance              = TermsOfUseAcceptance(
        responsibleIndividual,
        acceptanceDate,
        submissionId,
        0
      )
      val applicationId           = ApplicationId.random
      val importantSubmissionData = ImportantSubmissionData(
        None,
        responsibleIndividual,
        Set.empty,
        TermsAndConditionsLocations.InDesktopSoftware,
        PrivacyPolicyLocations.InDesktopSoftware,
        termsOfUseAcceptances = List()
      )
      val application             = anApplicationDataForTest(applicationId).withAccess(
        Access.Standard(importantSubmissionData = Some(importantSubmissionData))
      )
      await(applicationRepository.save(application))
      val updatedApplication      = await(
        applicationRepository.addApplicationTermsOfUseAcceptance(
          applicationId,
          acceptance
        )
      )

      val termsOfUseAcceptances = updatedApplication.access
        .asInstanceOf[Access.Standard]
        .importantSubmissionData
        .get
        .termsOfUseAcceptances
      termsOfUseAcceptances.size mustBe 1

      val termsOfUseAcceptance = termsOfUseAcceptances.head
      termsOfUseAcceptance.responsibleIndividual mustBe responsibleIndividual
      termsOfUseAcceptance.dateTime
        .toEpochMilli mustBe acceptanceDate
        .toEpochMilli
      termsOfUseAcceptance.submissionId mustBe submissionId
      termsOfUseAcceptance.submissionInstance mustBe 0
    }
  }

  "updateApplicationImportantSubmissionData" should {
    "update the application correctly" in {
      val responsibleIndividual   = ResponsibleIndividual(
        FullName("bob"),
        LaxEmailAddress("bob@example.com")
      )
      val applicationId           = ApplicationId.random
      val organisationUrl         = "http://anycorp.com"
      val importantSubmissionData = ImportantSubmissionData(
        Some(organisationUrl),
        responsibleIndividual,
        Set.empty,
        TermsAndConditionsLocations.InDesktopSoftware,
        PrivacyPolicyLocations.InDesktopSoftware,
        termsOfUseAcceptances = List()
      )
      val application             = anApplicationDataForTest(applicationId)

      await(applicationRepository.save(application))
      val updatedApplication = await(
        applicationRepository.updateApplicationImportantSubmissionData(
          applicationId,
          importantSubmissionData
        )
      )

      val actualImportantSubmissionData = updatedApplication.access
        .asInstanceOf[Access.Standard]
        .importantSubmissionData
      actualImportantSubmissionData.isDefined mustBe true

      actualImportantSubmissionData.get.responsibleIndividual mustBe responsibleIndividual
      actualImportantSubmissionData.get.organisationUrl mustBe Some(organisationUrl)
    }
  }

  "updateClientSecretHash" should {
    "overwrite an existing hashedSecretField" in {

      val clientSecret =
        aClientSecret(name = "secret-name", hashedSecret = "old-hashed-secret")

      val savedApplication = await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(clientSecret)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretHash(
          applicationId,
          clientSecret.id,
          "new-hashed-secret"
        )
      )

      savedApplication.tokens.production.clientSecrets.head.hashedSecret must be(
        "old-hashed-secret"
      )
      updatedApplication.tokens.production.clientSecrets.head.hashedSecret must be(
        "new-hashed-secret"
      )
    }

    "update correct client secret where there are multiple" in {

      val clientSecret1 = aClientSecret(name = "secret-name-1", hashedSecret = "old-hashed-secret-1")
      val clientSecret2 = aClientSecret(name = "secret-name-2", hashedSecret = "old-hashed-secret-2")
      val clientSecret3 = aClientSecret(name = "secret-name-3", hashedSecret = "old-hashed-secret-3")

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets = List(clientSecret1, clientSecret2, clientSecret3)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.updateClientSecretHash(
          applicationId,
          clientSecret2.id,
          "new-hashed-secret-2"
        )
      )

      val updatedClientSecrets =
        updatedApplication.tokens.production.clientSecrets
      updatedClientSecrets
        .find(_.id == clientSecret2.id)
        .get
        .hashedSecret must be("new-hashed-secret-2")

      updatedClientSecrets
        .find(_.id == clientSecret1.id)
        .get
        .hashedSecret must be("old-hashed-secret-1")
      updatedClientSecrets
        .find(_.id == clientSecret3.id)
        .get
        .hashedSecret must be("old-hashed-secret-3")
    }
  }

  "deleteClientSecret" should {
    "remove client secret with matching id" in {
      val clientSecretToRemove = aClientSecret(name = "secret-name-1", hashedSecret = "old-hashed-secret-1")
      val clientSecret2        = aClientSecret(name = "secret-name-2", hashedSecret = "old-hashed-secret-2")
      val clientSecret3        = aClientSecret(name = "secret-name-3", hashedSecret = "old-hashed-secret-3")

      await(
        applicationRepository.save(
          anApplicationDataForTest(
            applicationId,
            clientSecrets =
              List(clientSecretToRemove, clientSecret2, clientSecret3)
          )
        )
      )

      val updatedApplication = await(
        applicationRepository.deleteClientSecret(
          applicationId,
          clientSecretToRemove.id
        )
      )

      val updatedClientSecrets =
        updatedApplication.tokens.production.clientSecrets
      updatedClientSecrets.find(_.id == clientSecret2.id) mustBe (Some(
        clientSecret2
      ))
      updatedClientSecrets.find(_.id == clientSecretToRemove.id) mustBe (None)
      updatedClientSecrets.find(_.id == clientSecret3.id) mustBe (Some(
        clientSecret3
      ))
    }
  }

  "fetchAllForUserId" should {
    "return two applications when all have the same userId" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random

      val collaborator     = "user@example.com".admin(userId)
      val testApplication1 = anApplicationDataForTest(applicationId1).withCollaborators(collaborator)
      val testApplication2 = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb")).withCollaborators(collaborator)
      val testApplication3 = anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc")).withCollaborators(collaborator).withState(appStateDeleted)

      await(applicationRepository.save(testApplication1))
      await(applicationRepository.save(testApplication2))
      await(applicationRepository.save(testApplication3))

      val result = await(applicationRepository.fetchAllForUserId(userId, false))

      result.size mustBe 2
      result.map(
        _.collaborators.map(collaborator => collaborator.userId mustBe userId)
      )
    }

    "return three applications when all have the same userId and one is deleted" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random

      val collaborator     = "user@example.com".admin(userId)
      val testApplication1 = anApplicationDataForTest(applicationId1).withCollaborators(collaborator)
      val testApplication2 = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb")).withCollaborators(collaborator)
      val testApplication3 = anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc")).withCollaborators(collaborator).withState(appStateDeleted)

      await(applicationRepository.save(testApplication1))
      await(applicationRepository.save(testApplication2))
      await(applicationRepository.save(testApplication3))

      val result = await(applicationRepository.fetchAllForUserId(userId, true))

      result.size mustBe 3
      result.map(
        _.collaborators.map(collaborator => collaborator.userId mustBe userId)
      )
    }
  }

  "fetchAllForUserIdAndEnvironment" should {
    "return one application when 3 apps have the same userId but only one is in Production and not deleted" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random
      val productionEnv  = Environment.PRODUCTION

      val collaborator = "user@example.com".admin(userId)

      val prodApplication1   = anApplicationDataForTest(applicationId1).withCollaborators(collaborator)
      val prodApplication2   = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb")).withCollaborators(collaborator).withState(appStateDeleted)
      val sandboxApplication = anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc")).withCollaborators(collaborator).inSandbox()

      await(applicationRepository.save(prodApplication1))
      await(applicationRepository.save(prodApplication2))
      await(applicationRepository.save(sandboxApplication))

      val result = await(
        applicationRepository.fetchAllForUserIdAndEnvironment(
          userId,
          productionEnv
        )
      )

      result.size mustBe 1
      result.head.environment mustBe productionEnv
      result.map(
        _.collaborators.map(collaborator => collaborator.userId mustBe userId)
      )
    }
  }

  "fetchAllForEmailAddressAndEnvironment" should {
    "return one application when 3 apps have the same user email but only one is in Production and not deleted" in {
      val applicationId1 = ApplicationId.random
      val applicationId2 = ApplicationId.random
      val applicationId3 = ApplicationId.random
      val userId         = UserId.random
      val productionEnv  = Environment.PRODUCTION

      val collaborator: Collaborator = "user@example.com".admin(userId)

      val prodApplication1   = anApplicationDataForTest(applicationId1).copy(environment = productionEnv).withCollaborators(collaborator)
      val prodApplication2   = anApplicationDataForTest(applicationId2, prodClientId = ClientId("bbb")).withCollaborators(collaborator).withState(appStateDeleted)
      val sandboxApplication = anApplicationDataForTest(applicationId3, prodClientId = ClientId("ccc")).withCollaborators(collaborator).inSandbox()

      await(applicationRepository.save(prodApplication1))
      await(applicationRepository.save(prodApplication2))
      await(applicationRepository.save(sandboxApplication))

      val result = await(
        applicationRepository.fetchAllForEmailAddressAndEnvironment(
          collaborator.emailAddress.text,
          productionEnv
        )
      )

      result.size mustBe 1
      result.head.environment mustBe productionEnv
      result.map(
        _.collaborators.map(x =>
          x.emailAddress mustBe collaborator.emailAddress
        )
      )
    }
  }

  "documentsWithFieldMissing" should {
    "return count of documents with missing description" in {
      val appWithNoDescription = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .copy(description = None)
      val appWithDescription   = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
        .copy(description = Some("A description"))

      await(applicationRepository.save(appWithNoDescription))
      await(applicationRepository.save(appWithDescription))

      val numberRetrieved =
        await(applicationRepository.documentsWithFieldMissing("description"))

      numberRetrieved mustBe 1
    }
  }

  "count" should {
    "return count of documents in the collection" in {
      val application1 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )
      val application2 = anApplicationDataForTest(
        id = ApplicationId.random,
        prodClientId = generateClientId
      )

      await(applicationRepository.save(application1))
      await(applicationRepository.save(application2))

      val numberRetrieved = await(applicationRepository.count)

      numberRetrieved mustBe 2
    }
  }

  "handle addCollaborator correctly" in {
    val app = storedApp
    await(applicationRepository.save(app))

    val collaborator          = "email".developer()
    val existingCollaborators = app.collaborators

    val appWithNewCollaborator = await(applicationRepository.addCollaborator(applicationId, collaborator))
    appWithNewCollaborator.collaborators must contain only (existingCollaborators.toList ++ List(collaborator): _*)
  }

  "handle removeCollaborator correctly" in {
    val applicationId = ApplicationIdData.one

    val developerCollaborator = "email".developer()
    val adminCollaborator     = "email2".admin()
    val app                   = storedApp.copy(collaborators = Set(developerCollaborator, adminCollaborator))
    await(applicationRepository.save(app))

    val existingCollaborators = app.collaborators
    val userIdToDelete        = existingCollaborators.head.userId

    val appWithOutDeletedCollaborator = await(applicationRepository.removeCollaborator(applicationId, userIdToDelete))
    appWithOutDeletedCollaborator.collaborators must contain only (existingCollaborators.toList.filterNot(_.userId == userIdToDelete): _*)
  }

  "handle ProductionAppPrivacyPolicyLocationChanged correctly" in {
    val applicationId = ApplicationIdData.one

    val oldLocation = PrivacyPolicyLocations.InDesktopSoftware
    val newLocation = PrivacyPolicyLocations.Url("http://example.com")
    val access      = Access.Standard(
      List.empty,
      None,
      None,
      Set.empty,
      None,
      Some(
        ImportantSubmissionData(
          None,
          ResponsibleIndividual.build("bob example", "bob@example.com"),
          Set.empty,
          TermsAndConditionsLocations.InDesktopSoftware,
          oldLocation,
          List.empty
        )
      )
    )
    val app         = storedApp.withAccess(access)
    await(applicationRepository.save(app))

    val appWithUpdatedPrivacyPolicyLocation = await(applicationRepository.updateApplicationPrivacyPolicyLocation(applicationId, newLocation))
    appWithUpdatedPrivacyPolicyLocation.access match {
      case Access.Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, _, privacyPolicyLocation, _))) => privacyPolicyLocation mustBe newLocation
      case _                                                                                                   => fail("unexpected access type: " + appWithUpdatedPrivacyPolicyLocation.access)
    }
  }

  "handle LegacyAppPrivacyPolicyLocationChanged correctly" in {

    val oldUrl = "http://example.com/old"
    val newUrl = "http://example.com/new"
    val access = Access.Standard(List.empty, None, Some(oldUrl), Set.empty, None, None)
    val app    = storedApp.withAccess(access)
    await(applicationRepository.save(app))

    val appWithUpdatedPrivacyPolicyLocation = await(applicationRepository.updateLegacyPrivacyPolicyUrl(applicationId, Some(newUrl)))
    appWithUpdatedPrivacyPolicyLocation.access match {
      case Access.Standard(_, _, Some(privacyPolicyUrl), _, _, None) => privacyPolicyUrl mustBe newUrl
      case Access.Standard(_, _, None, _, _, None)                   => fail("unexpected lack of privacyPolicyUrl")
      case _                                                         => fail("unexpected access type: " + appWithUpdatedPrivacyPolicyLocation.access)
    }
  }

  "handle ProductionAppTermsConditionsLocationChanged event correctly" in {

    val oldLocation = TermsAndConditionsLocations.InDesktopSoftware
    val newLocation = TermsAndConditionsLocations.Url("http://example.com")
    val access      = Access.Standard(
      List.empty,
      None,
      None,
      Set.empty,
      None,
      Some(
        ImportantSubmissionData(None, ResponsibleIndividual.build("bob example", "bob@example.com"), Set.empty, oldLocation, PrivacyPolicyLocations.InDesktopSoftware, List.empty)
      )
    )
    val app         = storedApp.withAccess(access)
    await(applicationRepository.save(app))

    val appWithUpdatedTermsConditionsLocation = await(applicationRepository.updateApplicationTermsAndConditionsLocation(applicationId, newLocation))
    appWithUpdatedTermsConditionsLocation.access match {
      case Access.Standard(_, _, _, _, _, Some(ImportantSubmissionData(_, _, _, termsAndConditionsLocation, _, _))) => termsAndConditionsLocation mustBe newLocation
      case _                                                                                                        => fail("unexpected access type: " + appWithUpdatedTermsConditionsLocation.access)
    }
  }

  "handle ProductionLegacyAppTermsConditionsLocationChanged event correctly" in {

    val oldUrl = "http://example.com/old"
    val newUrl = "http://example.com/new"
    val access = Access.Standard(List.empty, Some(oldUrl), None, Set.empty, None, None)
    val app    = storedApp.withAccess(access)
    await(applicationRepository.save(app))

    val appWithUpdatedTermsConditionsLocation = await(applicationRepository.updateLegacyTermsAndConditionsUrl(applicationId, Some(newUrl)))
    appWithUpdatedTermsConditionsLocation.access match {
      case Access.Standard(_, Some(termsAndConditionsUrl), _, _, _, None) => termsAndConditionsUrl mustBe newUrl
      case _                                                              => fail("unexpected access type: " + appWithUpdatedTermsConditionsLocation.access)
    }
  }

  "handle updateApplicationState correctly" in {
    val oldRi                   = ResponsibleIndividual.build("old ri name", "old@example.com")
    val importantSubmissionData =
      ImportantSubmissionData(None, oldRi, Set.empty, TermsAndConditionsLocations.InDesktopSoftware, PrivacyPolicyLocations.InDesktopSoftware, List.empty)
    val access                  = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    val app                     = storedApp.withAccess(access)
    app.state.name mustBe State.PRODUCTION

    await(applicationRepository.save(app))
    val appWithUpdatedState = await(applicationRepository.updateApplicationState(applicationId, State.PENDING_GATEKEEPER_APPROVAL, instant, adminOne.emailAddress.text, adminName))
    appWithUpdatedState.state.name mustBe State.PENDING_GATEKEEPER_APPROVAL
    appWithUpdatedState.state.updatedOn mustBe instant
    appWithUpdatedState.state.requestedByEmailAddress mustBe Some(adminOne.emailAddress.text)
    appWithUpdatedState.state.requestedByName mustBe Some(adminName)
  }

  "handle updateApplicationChangeResponsibleIndividualToSelf correctly" in {

    val oldRi                   = ResponsibleIndividual.build("old ri name", "old@example.com")
    val submissionId            = SubmissionId.random
    val submissionIndex         = 1
    val importantSubmissionData = ImportantSubmissionData(
      None,
      oldRi,
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List(TermsOfUseAcceptance(oldRi, instant, submissionId, submissionIndex))
    )
    val access                  = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    val app                     = storedApp.withAccess(access)
    await(applicationRepository.save(app))

    val appWithUpdatedRI =
      await(applicationRepository.updateApplicationChangeResponsibleIndividualToSelf(applicationId, adminName, adminOne.emailAddress, instant, submissionId, submissionIndex))

    appWithUpdatedRI.access match {
      case Access.Standard(_, _, _, _, _, Some(importantSubmissionData)) => {
        importantSubmissionData.responsibleIndividual.fullName.value mustBe adminName
        importantSubmissionData.responsibleIndividual.emailAddress mustBe adminOne.emailAddress
        importantSubmissionData.termsOfUseAcceptances.size mustBe 2
        val latestAcceptance = importantSubmissionData.termsOfUseAcceptances(1)
        latestAcceptance.responsibleIndividual.fullName.value mustBe adminName
        latestAcceptance.responsibleIndividual.emailAddress mustBe adminOne.emailAddress
      }
      case _                                                             => fail("unexpected access type: " + appWithUpdatedRI.access)
    }
  }

  "handle NameChanged event correctly" in {
    val oldName = ApplicationName("oldName")
    val newName = "newName"

    val app = storedApp.copy(name = oldName)
    await(applicationRepository.save(app))

    val appWithUpdatedName = await(applicationRepository.updateApplicationName(applicationId, newName))
    appWithUpdatedName.name.value mustBe newName
    appWithUpdatedName.normalisedName mustBe newName.toLowerCase

    await(applicationRepository.hardDelete(applicationId))
  }

  "handle updateApplicationChangeResponsibleIndividual" in {
    val riName                  = "Mr Responsible"
    val riEmail                 = "ri@example.com".toLaxEmail
    val oldRi                   = ResponsibleIndividual.build("old ri name", "old@example.com")
    val submissionId            = SubmissionId.random
    val submissionIndex         = 1
    val importantSubmissionData = ImportantSubmissionData(
      None,
      oldRi,
      Set.empty,
      TermsAndConditionsLocations.InDesktopSoftware,
      PrivacyPolicyLocations.InDesktopSoftware,
      List(TermsOfUseAcceptance(oldRi, instant, submissionId, submissionIndex))
    )
    val access                  = Access.Standard(List.empty, None, None, Set.empty, None, Some(importantSubmissionData))
    val app                     = storedApp.withAccess(access)
    println(await(applicationRepository.save(app)))

    val appWithUpdatedRI = await(applicationRepository.updateApplicationChangeResponsibleIndividual(applicationId, riName, riEmail, instant, submissionId, submissionIndex))
    println(appWithUpdatedRI)

    appWithUpdatedRI.access match {
      case Access.Standard(_, _, _, _, _, Some(importantSubmissionData)) => {
        importantSubmissionData.responsibleIndividual.fullName.value mustBe riName
        importantSubmissionData.responsibleIndividual.emailAddress mustBe riEmail
        importantSubmissionData.termsOfUseAcceptances.size mustBe 2
        val latestAcceptance = importantSubmissionData.termsOfUseAcceptances(1)
        latestAcceptance.responsibleIndividual.fullName.value mustBe riName
        latestAcceptance.responsibleIndividual.emailAddress mustBe riEmail
      }
      case _                                                             => fail("unexpected access type: " + appWithUpdatedRI.access)
    }
  }

  "fetchProdAppStateHistories" should {
    def saveApp(state: State, timeOffset: Duration, isNewJourney: Boolean = true, environment: Environment = Environment.PRODUCTION) = {
      val app = storedApp.copy(
        id = ApplicationId.random,
        state = ApplicationState(name = state, updatedOn = instant),
        access = Access.Standard(importantSubmissionData = isNewJourney match {
          case true  => Some(ImportantSubmissionData(
              None,
              ResponsibleIndividual.build("ri name", "ri@example.com"),
              Set.empty,
              TermsAndConditionsLocations.InDesktopSoftware,
              PrivacyPolicyLocations.InDesktopSoftware,
              List.empty
            ))
          case false => None
        }),
        createdOn = instant.plus(timeOffset),
        environment = environment,
        tokens = ApplicationTokens(StoredToken(ClientId.random, "access token"))
      )
      await(applicationRepository.save(app))
      app
    }

    def saveHistoryStatePair(appId: ApplicationId, oldState: State, newState: State, timeOffset: Duration)     = saveHistory(appId, Some(oldState), newState, timeOffset)
    def saveHistory(appId: ApplicationId, maybeOldState: Option[State], newState: State, timeOffset: Duration) = {
      val stateHistory =
        StateHistory(appId, newState, Actors.GatekeeperUser("actor"), maybeOldState, None, instant.plus(timeOffset))
      await(stateHistoryRepository.insert(stateHistory))
      stateHistory
    }

    "return app state history correctly for new journey app" in {
      val app           = saveApp(State.PRODUCTION, Duration.ZERO, true)
      val stateHistory1 = saveHistoryStatePair(app.id, State.TESTING, State.PENDING_REQUESTER_VERIFICATION, Duration.ofHours(1))
      val stateHistory2 = saveHistoryStatePair(app.id, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_GATEKEEPER_APPROVAL, Duration.ofHours(2))
      val stateHistory3 = saveHistoryStatePair(app.id, State.PENDING_GATEKEEPER_APPROVAL, State.PRODUCTION, Duration.ofHours(3))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(ApplicationWithStateHistory(app.id, app.name.value, 2, List(stateHistory1, stateHistory2, stateHistory3)))
    }

    "return app state history correctly for old journey app" in {
      val app           = saveApp(State.PRODUCTION, Duration.ZERO, false)
      val stateHistory1 = saveHistoryStatePair(app.id, State.TESTING, State.PENDING_REQUESTER_VERIFICATION, Duration.ofHours(1))
      val stateHistory2 = saveHistoryStatePair(app.id, State.PENDING_REQUESTER_VERIFICATION, State.PENDING_GATEKEEPER_APPROVAL, Duration.ofHours(2))
      val stateHistory3 = saveHistoryStatePair(app.id, State.PENDING_GATEKEEPER_APPROVAL, State.PRODUCTION, Duration.ofHours(3))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(ApplicationWithStateHistory(app.id, app.name.value, 1, List(stateHistory1, stateHistory2, stateHistory3)))
    }

    "return app state histories sorted correctly" in {
      val app1        = saveApp(State.PRODUCTION, Duration.ofHours(1))
      val app1History = saveHistory(app1.id, None, State.TESTING, Duration.ofHours(1))

      val app2        = saveApp(State.PRODUCTION, Duration.ofHours(3))
      val app2History = saveHistory(app2.id, None, State.TESTING, Duration.ofHours(3))

      val app3        = saveApp(State.PRODUCTION, Duration.ofHours(2))
      val app3History = saveHistory(app3.id, None, State.TESTING, Duration.ofHours(2))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(
        ApplicationWithStateHistory(app1.id, app1.name.value, 2, List(app1History)),
        ApplicationWithStateHistory(app3.id, app2.name.value, 2, List(app3History)),
        ApplicationWithStateHistory(app2.id, app3.name.value, 2, List(app2History))
      )
    }

    "return only prod app state histories and not sandbox" in {
      val prodApp       = saveApp(State.PRODUCTION, Duration.ofHours(1))
      val stateHistory1 = saveHistoryStatePair(prodApp.id, State.TESTING, State.PENDING_GATEKEEPER_APPROVAL, Duration.ofHours(1))
      val stateHistory2 = saveHistoryStatePair(prodApp.id, State.PENDING_GATEKEEPER_APPROVAL, State.PRODUCTION, Duration.ofHours(2))

      val sandboxApp = saveApp(State.PRODUCTION, Duration.ofHours(1), true, Environment.SANDBOX)
      saveHistory(sandboxApp.id, None, State.PRODUCTION, Duration.ofHours(1))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List(ApplicationWithStateHistory(prodApp.id, prodApp.name.value, 2, List(stateHistory1, stateHistory2)))
    }

    "do not return app state history for a deleted app" in {
      val app = saveApp(State.DELETED, Duration.ZERO, true)
      saveHistoryStatePair(app.id, State.TESTING, State.PENDING_REQUESTER_VERIFICATION, Duration.ofHours(1))
      saveHistoryStatePair(app.id, State.PENDING_REQUESTER_VERIFICATION, State.DELETED, Duration.ofHours(2))

      val results = await(applicationRepository.fetchProdAppStateHistories())
      results mustBe List.empty
    }
  }

  "getSubscriptionsForDeveloper" should {
    val developerEmail1 = "john.doe@example.com"
    val developerEmail2 = "someone-else@example.com"

    val user1 = developerEmail1.developer()
    val user2 = developerEmail2.developer()

    "return only the APIs that the user's apps are subscribed to, without duplicates" in {
      val app1            = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId).withCollaborators(user1)
      await(applicationRepository.save(app1))
      val app2            = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId).withCollaborators(user1)
      await(applicationRepository.save(app2))
      val someoneElsesApp = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId).withCollaborators(user2)
      await(applicationRepository.save(someoneElsesApp))

      val helloWorldApi1 = "hello-world".asIdentifier("1.0")
      val helloWorldApi2 = "hello-world".asIdentifier("2.0")
      val helloVatApi    = "hello-vat".asIdentifier("1.0")
      val helloAgentsApi = "hello-agents".asIdentifier("1.0")

      await(subscriptionRepository.add(app1.id, helloWorldApi1))
      await(subscriptionRepository.add(app1.id, helloVatApi))
      await(subscriptionRepository.add(app2.id, helloWorldApi2))
      await(subscriptionRepository.add(app2.id, helloVatApi))
      await(subscriptionRepository.add(someoneElsesApp.id, helloAgentsApi))

      val result: Set[ApiIdentifier] = await(applicationRepository.getSubscriptionsForDeveloper(user1.userId))

      result mustBe Set(helloWorldApi1, helloVatApi, helloWorldApi2)
    }

    "return empty when the user is not a collaborator of any apps" in {
      val app1 = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId).withCollaborators(user2)
      val app2 = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId).withCollaborators(user1)

      await(applicationRepository.save(app1))
      await(applicationRepository.save(app2))

      val api = "hello-world".asIdentifier("1.0")
      await(subscriptionRepository.add(app1.id, api))

      val developerId                = app2.collaborators.head.userId
      val result: Set[ApiIdentifier] = await(applicationRepository.getSubscriptionsForDeveloper(developerId))

      result mustBe Set.empty
    }

    "return empty when the user's apps are not subscribed to any API" in {
      val app = anApplicationDataForTest(id = ApplicationId.random, prodClientId = generateClientId).withCollaborators(user1)
      await(applicationRepository.save(app))

      val developerId                = app.collaborators.head.userId
      val result: Set[ApiIdentifier] = await(applicationRepository.getSubscriptionsForDeveloper(developerId))

      result mustBe Set.empty
    }
  }
}
