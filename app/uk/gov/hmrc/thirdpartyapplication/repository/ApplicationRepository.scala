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

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import com.kenshoo.play.metrics.Metrics
import com.mongodb.client.model.{FindOneAndUpdateOptions, ReturnDocument}
import org.bson.BsonValue
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.{BsonArray, BsonInt32, BsonString, Document}
import org.mongodb.scala.model
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections.{excludeId, fields, include}
import org.mongodb.scala.model._

import play.api.libs.json.Json._
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util.MetricsTimer
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.RateLimitTier
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ClientSecret
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.PrivacyPolicyLocation
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.TermsAndConditionsLocation
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.TermsOfUseAcceptance
import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientSecretData
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ResponsibleIndividual
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.ImportantSubmissionData
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.Access
import uk.gov.hmrc.thirdpartyapplication.domain.models.Token

object ApplicationRepository {
  case class SubsByUser(apiIdentifiers: List[ApiIdentifier])

  object MongoFormats {
    implicit val subsByUserFormat = Json.format[SubsByUser]

    implicit val dateFormat = MongoJavatimeFormats.localDateTimeFormat
    implicit val formatTermsOfUseAcceptance     = Json.format[TermsOfUseAcceptance]
    implicit val formatTermsOfUserAgreement    = Json.format[TermsOfUseAgreement]
    implicit val formatImportantSubmissionData = Json.format[ImportantSubmissionData]
    implicit val formatStandard                = Json.format[Access.Standard]
    implicit val formatPrivileged                = Json.format[Access.Privileged]
    implicit val formatRopc                    = Json.format[Access.Ropc]

    import uk.gov.hmrc.play.json.Union
    implicit val formatAccess = Union.from[Access]("accessType")
      .and[Access.Standard](AccessType.STANDARD.toString)
      .and[Access.Privileged](AccessType.PRIVILEGED.toString)
      .and[Access.Ropc](AccessType.ROPC.toString)
      .format

    implicit val formatCheckInformation = Json.format[CheckInformation]

    implicit val formatApplicationState  = Json.format[ApplicationState]
    implicit val formatClientSecret      = Json.format[ClientSecretData]
    implicit val formatEnvironmentToken  = Json.format[Token]
    implicit val formatApplicationTokens = Json.format[ApplicationTokens]
    
    import play.api.libs.functional.syntax._
    import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData.grantLengthConfig
    
    val applicationDataReads: Reads[ApplicationData] = (
      (JsPath \ "id").read[ApplicationId] and
        (JsPath \ "name").read[String] and
        (JsPath \ "normalisedName").read[String] and
        (JsPath \ "collaborators").read[Set[Collaborator]] and
        (JsPath \ "description").readNullable[String] and
        (JsPath \ "wso2ApplicationName").read[String] and
        (JsPath \ "tokens").read[ApplicationTokens] and
        (JsPath \ "state").read[ApplicationState] and
        (JsPath \ "access").read[Access] and
        (JsPath \ "createdOn").read[LocalDateTime] and
        (JsPath \ "lastAccess").readNullable[LocalDateTime] and
        ((JsPath \ "grantLength").read[Int] or Reads.pure(grantLengthConfig)) and
        (JsPath \ "rateLimitTier").readNullable[RateLimitTier] and
        (JsPath \ "environment").read[String] and
        (JsPath \ "checkInformation").readNullable[CheckInformation] and
        ((JsPath \ "blocked").read[Boolean] or Reads.pure(false)) and
        ((JsPath \ "ipAllowlist").read[IpAllowlist] or Reads.pure(IpAllowlist())) and
        ((JsPath \ "allowAutoDelete").read[Boolean] or Reads.pure(true))
    )(ApplicationData.apply _)

    implicit val formatApplicationData: OFormat[ApplicationData] = OFormat(applicationDataReads, Json.writes[ApplicationData])  

    implicit val reads             = Json.reads[PaginatedApplicationData]
  }
}

@Singleton
class ApplicationRepository @Inject() (mongo: MongoComponent, val metrics: Metrics)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[ApplicationData](
      collectionName = "application",
      mongoComponent = mongo,
      domainFormat = ApplicationRepository.MongoFormats.formatApplicationData,
      indexes = Seq(
        IndexModel(
          ascending("state.verificationCode"),
          IndexOptions()
            .name("verificationCodeIndex")
            .background(true)
        ),
        IndexModel(
          ascending("state.name", "state.updatedOn"),
          IndexOptions()
            .name("stateName_stateUpdatedOn_Index")
            .background(true)
        ),
        IndexModel(
          ascending("id"),
          IndexOptions()
            .name("applicationIdIndex")
            .unique(true)
            .background(true)
        ),
        IndexModel(
          ascending("normalisedName"),
          IndexOptions()
            .name("applicationNormalisedNameIndex")
            .background(true)
        ),
        IndexModel(
          ascending("lastAccess"),
          IndexOptions()
            .name("lastAccessIndex")
            .background(true)
        ),
        IndexModel(
          ascending("tokens.production.clientId"),
          IndexOptions()
            .name("productionTokenClientIdIndex")
            .unique(true)
            .background(true)
        ),
        IndexModel(
          ascending("access.overrides"),
          IndexOptions()
            .name("accessOverridesIndex")
            .background(true)
        ),
        IndexModel(
          ascending("access.accessType"),
          IndexOptions()
            .name("accessTypeIndex")
            .background(true)
        ),
        IndexModel(
          ascending("collaborators.emailAddress"),
          IndexOptions()
            .name("collaboratorsEmailAddressIndex")
            .background(true)
        ),
        IndexModel(
          ascending("collaborators.userId"),
          IndexOptions()
            .name("collaboratorsUserIdIndex")
            .background(true)
        )
      ),
      replaceIndexes = true,
      extraCodecs = Seq(Codecs.playFormatCodec(LaxEmailAddress.format))
    ) with MetricsTimer
    with ApplicationLogger {

  import ApplicationRepository.MongoFormats._

  def save(application: ApplicationData): Future[ApplicationData] = {
    val query = equal("id", Codecs.toBson(application.id))
    collection.find(query).headOption().flatMap {
      case Some(_: ApplicationData) =>
        collection.replaceOne(
          filter = query,
          replacement = application
        ).toFuture().map(_ => application)

      case None => collection.insertOne(application).toFuture().map(_ => application)
    }
  }

  def updateAllowAutoDelete(applicationId: ApplicationId, allowAutoDelete: Boolean): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("allowAutoDelete", Codecs.toBson(allowAutoDelete)))

  def updateApplicationRateLimit(applicationId: ApplicationId, rateLimit: RateLimitTier): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("rateLimitTier", Codecs.toBson(rateLimit)))

  def updateApplicationIpAllowlist(applicationId: ApplicationId, ipAllowlist: IpAllowlist): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("ipAllowlist", Codecs.toBson(ipAllowlist)))

  def updateApplicationGrantLength(applicationId: ApplicationId, grantLength: Int): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("grantLength", grantLength))

  def addApplicationTermsOfUseAcceptance(applicationId: ApplicationId, acceptance: TermsOfUseAcceptance): Future[ApplicationData] =
    updateApplication(applicationId, Updates.push("access.importantSubmissionData.termsOfUseAcceptances", Codecs.toBson(acceptance)))

  def findAndRecordApplicationUsage(clientId: ClientId): Future[Option[ApplicationData]] = {
    timeFuture("Find and Record Application Usage", "application.repository.findAndRecordApplicationUsage") {
      val query = and(
        equal("tokens.production.clientId", Codecs.toBson(clientId)),
        notEqual("state.name", State.DELETED.toString())
      )
      collection.findOneAndUpdate(
        filter = query,
        update = Updates.currentDate("lastAccess"),
        options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      ).headOption()
    }
  }

  def findAndRecordServerTokenUsage(serverToken: String): Future[Option[ApplicationData]] = {
    timeFuture("Find and Record Application Server Token Usage", "application.repository.findAndRecordServerTokenUsage") {
      val query = and(
        equal("tokens.production.accessToken", serverToken),
        notEqual("state.name", State.DELETED.toString())
      )
      collection.findOneAndUpdate(
        filter = query,
        update = Updates.combine(Updates.currentDate("lastAccess"), Updates.currentDate("tokens.production.lastAccessTokenUsage")),
        options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      ).headOption()
    }
  }

  // Historically emailAddress was the unique identifier for User and it didn't have a userId.
  // So this method was to back fix any records without the userId.
  // This is difficult to test as the model does not allow a User without a userId.
  // $COVERAGE-OFF$
  def updateCollaboratorId(applicationId: ApplicationId, collaboratorEmailAddress: String, collaboratorUser: UserId): Future[Option[ApplicationData]] = {
    val query = and(
      equal("id", Codecs.toBson(applicationId)),
      elemMatch(
        "collaborators",
        and(
          equal("emailAddress", collaboratorEmailAddress),
          exists("userId", exists = false)
        )
      )
    )

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.set("collaborators.$.userId", Codecs.toBson(collaboratorUser)),
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFutureOption()
  }
  // $COVERAGE-ON$

  def updateApplication(applicationId: ApplicationId, updateStatement: Bson): Future[ApplicationData] = {
    val query = equal("id", Codecs.toBson(applicationId))

    collection.findOneAndUpdate(
      filter = query,
      update = updateStatement,
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def updateClientSecretField(applicationId: ApplicationId, clientSecretId: ClientSecret.Id, fieldName: String, fieldValue: String): Future[ApplicationData] = {
    val query = and(
      equal("id", Codecs.toBson(applicationId)),
      equal("tokens.production.clientSecrets.id", Codecs.toBson(clientSecretId))
    )

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.set(s"tokens.production.clientSecrets.$$.$fieldName", fieldValue),
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def addClientSecret(applicationId: ApplicationId, clientSecret: ClientSecretData): Future[ApplicationData] =
    updateApplication(applicationId, Updates.push("tokens.production.clientSecrets", Codecs.toBson(clientSecret)))

  def updateClientSecretName(applicationId: ApplicationId, clientSecretId: ClientSecret.Id, newName: String): Future[ApplicationData] =
    updateClientSecretField(applicationId, clientSecretId, "name", newName)

  def updateClientSecretHash(applicationId: ApplicationId, clientSecretId: ClientSecret.Id, hashedSecret: String): Future[ApplicationData] =
    updateClientSecretField(applicationId, clientSecretId, "hashedSecret", hashedSecret)

  def recordClientSecretUsage(applicationId: ApplicationId, clientSecretId: ClientSecret.Id): Future[ApplicationData] = {
    timeFuture("Record Application Client Secret Usage", "application.repository.recordClientSecretUsage") {
      val query = and(
        equal("id", Codecs.toBson(applicationId)),
        equal("tokens.production.clientSecrets.id", Codecs.toBson(clientSecretId))
      )

      collection.findOneAndUpdate(
        filter = query,
        update = Updates.currentDate("tokens.production.clientSecrets.$.lastAccess"),
        options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      ).toFuture()
    }
  }

  def deleteClientSecret(applicationId: ApplicationId, clientSecretId: ClientSecret.Id): Future[ApplicationData] = {
    val query = equal("id", Codecs.toBson(applicationId))

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.pull("tokens.production.clientSecrets", Codecs.toBson(Json.obj("id" -> clientSecretId))),
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def fetchStandardNonTestingApps(): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("access.accessType", AccessType.STANDARD.toString()),
      notEqual("state.name", State.TESTING.toString()),
      notEqual("state.name", State.DELETED.toString())
    )

    collection.find(query).toFuture()
  }

  def fetch(id: ApplicationId): Future[Option[ApplicationData]] = {
    collection.find(equal("id", Codecs.toBson(id))).headOption()
  }

  def fetchApplicationsByName(name: String): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("normalisedName", name.toLowerCase),
      notEqual("state.name", State.DELETED.toString())
    )

    collection.find(query).toFuture()
  }

  def fetchVerifiableUpliftBy(verificationCode: String): Future[Option[ApplicationData]] = {
    val query = and(
      equal("state.verificationCode", verificationCode),
      notEqual("state.name", State.DELETED.toString())
    )

    collection.find(query).headOption()
  }

  def fetchAllByStatusDetails(state: State, updatedBefore: LocalDateTime): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("state.name", state.toString()),
      lte("state.updatedOn", updatedBefore)
    )

    collection.find(query).toFuture()
  }

  def fetchByStatusDetailsAndEnvironment(state: State, updatedBefore: LocalDateTime, environment: Environment): Future[Seq[ApplicationData]] = {
    collection.aggregate(
      Seq(
        filter(equal("state.name", state.toString())),
        filter(equal("environment", Codecs.toBson(environment))),
        filter(lte("state.updatedOn", updatedBefore))
      )
    ).toFuture()
  }

  def fetchByStatusDetailsAndEnvironmentNotAleadyNotified(state: State, updatedBefore: LocalDateTime, environment: Environment): Future[Seq[ApplicationData]] = {
    timeFuture(
      "Fetch Applications by Status Details and Environment not Already Notified",
      "application.repository.fetchByStatusDetailsAndEnvironmentNotAleadyNotified"
    ) {
      collection.aggregate(
        Seq(
          filter(equal("state.name", state.toString())),
          filter(equal("environment", Codecs.toBson(environment))),
          filter(lte("state.updatedOn", updatedBefore)),
          lookup(from = "notifications", localField = "id", foreignField = "applicationId", as = "matched"),
          filter(size("matched", 0))
        )
      ).toFuture()
    }
  }

  def fetchByClientId(clientId: ClientId): Future[Option[ApplicationData]] = {
    timeFuture("Fetch Application by ClientId", "application.repository.fetchByClientId") {
      val query = and(
        equal("tokens.production.clientId", Codecs.toBson(clientId)),
        notEqual("state.name", State.DELETED.toString())
      )

      collection.find(query).headOption()
    }
  }

  def fetchByServerToken(serverToken: String): Future[Option[ApplicationData]] = {
    timeFuture("Fetch Application by Server Token", "application.repository.fetchByServerToken") {
      val query = and(
        equal("tokens.production.accessToken", serverToken),
        notEqual("state.name", State.DELETED.toString())
      )

      collection.find(query).headOption()
    }
  }

  def fetchAllForUserId(userId: UserId, includeDeleted: Boolean): Future[Seq[ApplicationData]] = {
    timeFuture("Fetch All Applications for UserId", "application.repository.fetchAllForUserId") {
      def query = {
        if (includeDeleted) {
          equal("collaborators.userId", Codecs.toBson(userId))
        } else {
          and(
            equal("collaborators.userId", Codecs.toBson(userId)),
            notEqual("state.name", State.DELETED.toString())
          )
        }
      }

      collection.find(query).toFuture()
    }
  }

  def fetchAllForUserIdAndEnvironment(userId: UserId, environment: String): Future[Seq[ApplicationData]] = {
    timeFuture("Fetch All Applications for UserId and Environment", "application.repository.fetchAllForUserIdAndEnvironment") {
      val query = and(
        equal("collaborators.userId", Codecs.toBson(userId)),
        equal("environment", environment),
        notEqual("state.name", State.DELETED.toString())
      )

      collection.find(query).toFuture()
    }
  }

  /*
    db.application.aggregate( [
        {
            $match:
            {
                "collaborators.userId" : "85682eda-5758-4a13-8b97-057c94b3657b"
            }
        },
        {
            $lookup:
            {
                from: "subscription",
                localField: "id",
                foreignField: "applications",
                as: "subs"
            }
        },
        {
            $project:
            {
                _id: 0,
                "apiIdentifier": "$subs.apiIdentifier"
            }
        },
        {
            $unwind: "$apiIdentifier"
        },
        {
            $project: {
                _id: 0,
                "context": "$apiIdentifier.context",
                "version": "$apiIdentifier.version"
            }
        }
    ] )
   */
  def getSubscriptionsForDeveloper(userId: UserId): Future[Set[ApiIdentifier]] = {
    timeFuture("Get Subscriptions for Developer", "application.repository.getSubscriptionsForDeveloper") {

      import org.mongodb.scala.model.Projections.{computed, excludeId}

      val pipeline = Seq(
        matches(equal("collaborators.userId", Codecs.toBson(userId))),
        lookup(from = "subscription", localField = "id", foreignField = "applications", as = "subs"),
        project(
          fields(
            excludeId(),
            computed("apiIdentifier", "$subs.apiIdentifier")
          )
        ),
        unwind("$apiIdentifier"),
        project(
          fields(
            excludeId(),
            computed("context", "$apiIdentifier.context"),
            computed("version", "$apiIdentifier.version")
          )
        )
      )

      collection.aggregate[BsonValue](pipeline)
        .map(Codecs.fromBson[ApiIdentifier])
        .toFuture()
        .map(_.toSet)
    }
  }

  def fetchAllForEmailAddress(emailAddress: String): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("collaborators.emailAddress", emailAddress),
      notEqual("state.name", State.DELETED.toString())
    )

    collection.find(query).toFuture()
  }

  def fetchAllForEmailAddressAndEnvironment(emailAddress: String, environment: String): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("collaborators.emailAddress", emailAddress),
      equal("environment", environment),
      notEqual("state.name", State.DELETED.toString())
    )

    collection.find(query).toFuture()
  }

  def fetchProdAppStateHistories(): Future[Seq[ApplicationWithStateHistory]] = {
    timeFuture("Fetch Production Application State Histories", "application.repository.fetchProdAppStateHistories") {
      val pipeline: Seq[Bson] = Seq(
        matches(equal("environment", Codecs.toBson(Environment.PRODUCTION.toString()))),
        matches(notEqual("state.name", State.DELETED.toString())),
        addFields(Field("version", cond(Document("$not" -> BsonString("$access.importantSubmissionData")), 1, 2))),
        lookup(from = "stateHistory", localField = "id", foreignField = "applicationId", as = "states"),
        sort(ascending("createdOn", "states.changedAt"))
      )
      collection.aggregate[BsonValue](pipeline).map(Codecs.fromBson[ApplicationWithStateHistory]).toFuture()
    }
  }

  def searchApplications(actionSubtask: String)(applicationSearch: ApplicationSearch): Future[PaginatedApplicationData] = {
    timeFuture("Search Applications", s"application.repository.searchApplications.$actionSubtask") {
      val filters = applicationSearch.filters.map(filter => convertFilterToQueryClause(filter, applicationSearch)) ++ deletedFilter(applicationSearch)
      val sort    = convertToSortClause(applicationSearch.sort)

      val pagination = List(
        skip((applicationSearch.pageNumber - 1) * applicationSearch.pageSize),
        limit(applicationSearch.pageSize)
      )

      runAggregationQuery(filters, pagination, sort, applicationSearch.hasSubscriptionFilter(), applicationSearch.hasSpecificApiSubscriptionFilter())
    }
  }

  private def cond[T](condition: Document, trueValue: Int, falseValue: Int): Bson = {
    Document("$cond" -> BsonArray(
      condition,
      BsonInt32(trueValue),
      BsonInt32(falseValue)
    ))
  }

  private def deletedFilter(applicationSearch: ApplicationSearch): List[Bson] = {
    // Filter out Deleted applications, unless specifically asked for
    if (!applicationSearch.includeDeleted) {
      List(matches(notEqual("state.name", State.DELETED.toString())))
    } else {
      List()
    }
  }

  private def matches(predicates: Bson): Bson = filter(predicates)

  private def in(fieldName: String, values: Seq[String]): Bson = matches(Filters.in(fieldName, values: _*))

  // scalastyle:off cyclomatic.complexity
  private def convertFilterToQueryClause(applicationSearchFilter: ApplicationSearchFilter, applicationSearch: ApplicationSearch): Bson = {

    def applicationStatusMatch(states: State*): Bson = in("state.name", states.map(_.toString))

    def applicationStatusNotEqual(state: State): Bson = matches(notEqual("state.name", State.DELETED.toString()))

    def accessTypeMatch(accessType: AccessType): Bson = matches(equal("access.accessType", Codecs.toBson(accessType)))

    def allowAutoDeleteMatch(allowAutoDelete: Boolean): Bson = {
      allowAutoDelete match {
        case false => matches(equal("allowAutoDelete", Codecs.toBson(allowAutoDelete)))
        case true  => matches(or(equal("allowAutoDelete", Codecs.toBson(allowAutoDelete)), exists("allowAutoDelete", false)))
      }

    }

    def specificAPISubscription(apiContext: ApiContext, apiVersion: Option[ApiVersionNbr]) = {
      apiVersion.fold(
        matches(equal("subscribedApis.apiIdentifier.context", Codecs.toBson(apiContext)))
      )(version =>
        matches(
          Document(
            "subscribedApis.apiIdentifier.context" -> Codecs.toBson(apiContext),
            "subscribedApis.apiIdentifier.version" -> Codecs.toBson(version)
          )
        )
      )
    }

    applicationSearchFilter match {

      // API Subscriptions
      case NoAPISubscriptions        => matches(size("subscribedApis", 0))
      case OneOrMoreAPISubscriptions => matches(Document(s"""{$$expr: {$$gte: [{$$size:"$$subscribedApis"}, 1] }}"""))
      case SpecificAPISubscription   => specificAPISubscription(applicationSearch.apiContext.get, applicationSearch.apiVersion)

      // Application Status
      case Created                                  => applicationStatusMatch(State.TESTING)
      case PendingResponsibleIndividualVerification => applicationStatusMatch(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION)
      case PendingGatekeeperCheck                   => applicationStatusMatch(State.PENDING_GATEKEEPER_APPROVAL)
      case PendingSubmitterVerification             => applicationStatusMatch(State.PENDING_REQUESTER_VERIFICATION)
      case Active                                   => applicationStatusMatch(State.PRE_PRODUCTION, State.PRODUCTION)
      case WasDeleted                               => applicationStatusMatch(State.DELETED)
      case ExcludingDeleted                         => applicationStatusNotEqual(State.DELETED)

      // Access Type
      case StandardAccess   => accessTypeMatch(AccessType.STANDARD)
      case ROPCAccess       => accessTypeMatch(AccessType.ROPC)
      case PrivilegedAccess => accessTypeMatch(AccessType.PRIVILEGED)

      // Text Search
      case ApplicationTextSearch => regexTextSearch(List("id", "name", "tokens.production.clientId"), applicationSearch.textToSearch.getOrElse(""))

      // Last Use Date
      case lastUsedBefore: LastUseBeforeDate => lastUsedBefore.toMongoMatch
      case lastUsedAfter: LastUseAfterDate   => lastUsedAfter.toMongoMatch

      // Allow Auto Delete
      case AutoDeleteAllowed => allowAutoDeleteMatch(true)
      case AutoDeleteBlocked => allowAutoDeleteMatch(false)
      case _                 => Document() // Only here to complete the match
    }
  }
  // scalastyle:on cyclomatic.complexity

  private def convertToSortClause(sort: ApplicationSort): List[Bson] = sort match {
    case NameAscending         => List(Aggregates.sort(Sorts.ascending("normalisedName")))
    case NameDescending        => List(Aggregates.sort(Sorts.descending("normalisedName")))
    case SubmittedAscending    => List(Aggregates.sort(Sorts.ascending("createdOn")))
    case SubmittedDescending   => List(Aggregates.sort(Sorts.descending("createdOn")))
    case LastUseDateAscending  => List(Aggregates.sort(Sorts.ascending("lastAccess")))
    case LastUseDateDescending => List(Aggregates.sort(Sorts.descending("lastAccess")))
    case NoSorting             => List()
    case _                     => List(Aggregates.sort(Sorts.ascending("normalisedName")))
  }

  private def regexTextSearch(fields: List[String], searchText: String): Bson = {
    matches(or(fields.map(field => regex(field, searchText, "i")): _*))
  }

  private def runAggregationQuery(filters: List[Bson], pagination: List[Bson], sort: List[Bson], hasSubscriptionsQuery: Boolean, hasSpecificApiSubscription: Boolean) = {
    timeFuture("Run Application Aggregation Query", "application.repository.runAggregationQuery") {

      lazy val subscriptionsLookup: Bson  = lookup(from = "subscription", localField = "id", foreignField = "applications", as = "subscribedApis")
      lazy val unwindSubscribedApis: Bson = unwind("$subscribedApis")
      val applicationProjection: Bson     = project(fields(
        excludeId(),
        include(
          "id",
          "name",
          "normalisedName",
          "collaborators",
          "description",
          "wso2ApplicationName",
          "tokens",
          "state",
          "access",
          "createdOn",
          "lastAccess",
          "rateLimitTier",
          "environment",
          "allowAutoDelete"
        )
      ))

      val totalCount                                    = Aggregates.count("total")
      val subscriptionsLookupFilter                     = if (hasSubscriptionsQuery) Seq(subscriptionsLookup) else Seq.empty
      val subscriptionsLookupExtendedFilter             = if (hasSpecificApiSubscription) subscriptionsLookupFilter :+ unwindSubscribedApis else subscriptionsLookupFilter
      val filteredPipelineCount                         = subscriptionsLookupExtendedFilter ++ filters :+ totalCount
      val paginatedFilteredAndSortedPipeline: Seq[Bson] = subscriptionsLookupExtendedFilter ++ filters ++ sort ++ pagination :+ applicationProjection

      val facets: Seq[Bson] = Seq(
        facet(
          model.Facet("totals", totalCount),
          model.Facet("matching", filteredPipelineCount: _*),
          model.Facet("applications", paginatedFilteredAndSortedPipeline: _*)
        )
      )

      collection.aggregate[BsonValue](facets)
        .head()
        .map(Codecs.fromBson[PaginatedApplicationData])
        .map(d => PaginatedApplicationData(d.applications, d.totals, d.matching))
    }
  }

  def fetchAllForContext(apiContext: ApiContext): Future[List[ApplicationData]] =
    searchApplications("fetchAllForContext")(
      ApplicationSearch(
        filters = List(SpecificAPISubscription),
        apiContext = Some(apiContext)
      )
    ).map(_.applications)

  def fetchAllForApiIdentifier(apiIdentifier: ApiIdentifier): Future[List[ApplicationData]] =
    searchApplications("fetchAllForApiIdentifier")(
      ApplicationSearch(
        filters = List(SpecificAPISubscription),
        apiContext = Some(apiIdentifier.context),
        apiVersion = Some(apiIdentifier.versionNbr)
      )
    ).map(_.applications)

  def fetchAllWithNoSubscriptions(): Future[List[ApplicationData]] =
    searchApplications("fetchAllWithNoSubscriptions")(new ApplicationSearch(filters = List(NoAPISubscriptions))).map(_.applications)

  def fetchAll(): Future[List[ApplicationData]] = {
    val result = searchApplications("fetchAll")(new ApplicationSearch())

    result.map(_.applications)
  }

  def processAll(function: ApplicationData => Unit): Future[Unit] = {
    timeFuture("Process All Applications", "application.repository.processAll") {

      collection.find(notEqual("state.name", State.DELETED.toString()))
        .map(function)
        .toFuture()
        .map(_ => ())
    }
  }

  def hardDelete(id: ApplicationId): Future[HasSucceeded] = {
    collection.deleteOne(equal("id", Codecs.toBson(id)))
      .toFuture()
      .map(_ => HasSucceeded)
  }

  def delete(id: ApplicationId, updatedOn: LocalDateTime): Future[ApplicationData] = {
    updateApplication(
      id,
      Updates.combine(
        Updates.set("state.name", State.DELETED.toString()),
        Updates.set("state.updatedOn", updatedOn)
      )
    )
  }

  def documentsWithFieldMissing(fieldName: String): Future[Int] = {
    collection.countDocuments(exists(fieldName, false))
      .toFuture()
      .map(_.toInt)
  }

  def count: Future[Int] = {
    collection.countDocuments()
      .toFuture()
      .map(_.toInt)
  }

  def getApplicationWithSubscriptionCount(): Future[Map[String, Int]] = {
    timeFuture("Applications with Subscription Count", "application.repository.getApplicationWithSubscriptionCount") {

      val pipeline = Seq(
        matches(notEqual("state.name", State.DELETED.toString())),
        lookup(from = "subscription", localField = "id", foreignField = "applications", as = "subscribedApis"),
        unwind("$subscribedApis"),
        group(Document("id" -> "$id", "name" -> "$name"), Accumulators.sum("count", 1))
      )

      collection.aggregate[BsonValue](pipeline)
        .map(Codecs.fromBson[ApplicationWithSubscriptionCount])
        .toFuture()
        .map(_.map(x => s"applicationsWithSubscriptionCountV1.${sanitiseGrafanaNodeName(x._id.name)}" -> x.count)
          .toMap)
    }
  }

  def addCollaborator(applicationId: ApplicationId, collaborator: Collaborator) =
    updateApplication(
      applicationId,
      Updates.push(
        "collaborators",
        Codecs.toBson(collaborator)
      )
    )

  def removeCollaborator(applicationId: ApplicationId, userId: UserId) =
    updateApplication(
      applicationId,
      Updates.pull(
        "collaborators",
        Codecs.toBson(Json.obj("userId" -> userId))
      )
    )

  def updateRedirectUris(applicationId: ApplicationId, redirectUris: List[String]) =
    updateApplication(applicationId, Updates.set("access.redirectUris", Codecs.toBson(redirectUris)))

  def updateApplicationName(applicationId: ApplicationId, name: String): Future[ApplicationData] =
    updateApplication(
      applicationId,
      Updates.combine(
        Updates.set("name", name),
        Updates.set("normalisedName", name.toLowerCase)
      )
    )

  def updateApplicationPrivacyPolicyLocation(applicationId: ApplicationId, location: PrivacyPolicyLocation): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.importantSubmissionData.privacyPolicyLocation", Codecs.toBson(location)))

  def updateLegacyApplicationPrivacyPolicyLocation(applicationId: ApplicationId, url: String): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.privacyPolicyUrl", url))

  def updateApplicationTermsAndConditionsLocation(applicationId: ApplicationId, location: TermsAndConditionsLocation): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.importantSubmissionData.termsAndConditionsLocation", Codecs.toBson(location)))

  def updateLegacyApplicationTermsAndConditionsLocation(applicationId: ApplicationId, url: String): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.termsAndConditionsUrl", url))


  def updateApplicationChangeResponsibleIndividual(
      applicationId: ApplicationId,
      newResponsibleIndividualName: String,
      newResponsibleIndividualEmail: LaxEmailAddress,
      eventDateTime: LocalDateTime,
      submissionId: SubmissionId,
      submissionIndex: Int
    ): Future[ApplicationData] = {

    updateApplication(
      applicationId,
      Updates.combine(
        Updates.set("access.importantSubmissionData.responsibleIndividual.fullName", newResponsibleIndividualName),
        Updates.set("access.importantSubmissionData.responsibleIndividual.emailAddress", Codecs.toBson(newResponsibleIndividualEmail)),
        Updates.push(
          "access.importantSubmissionData.termsOfUseAcceptances",
          Codecs.toBson(TermsOfUseAcceptance(
            ResponsibleIndividual.build(newResponsibleIndividualName, newResponsibleIndividualEmail.text),
            eventDateTime,
            submissionId,
            submissionIndex
          ))
        )
      )
    )
  }

  def updateApplicationChangeResponsibleIndividualToSelf(
      applicationId: ApplicationId,
      requestingAdminName: String,
      requestingAdminEmail: LaxEmailAddress,
      timeOfChange: LocalDateTime,
      submissionId: SubmissionId,
      submissionIndex: Int
    ): Future[ApplicationData] =
    updateApplication(
      applicationId,
      Updates.combine(
        Updates.set("access.importantSubmissionData.responsibleIndividual.fullName", requestingAdminName),
        Updates.set("access.importantSubmissionData.responsibleIndividual.emailAddress", Codecs.toBson(requestingAdminEmail)),
        Updates.push(
          "access.importantSubmissionData.termsOfUseAcceptances",
          Codecs.toBson(TermsOfUseAcceptance(
            ResponsibleIndividual.build(requestingAdminName, requestingAdminEmail.text),
            timeOfChange,
            submissionId,
            submissionIndex
          ))
        )
      )
    )

  def updateApplicationSetResponsibleIndividual(
      applicationId: ApplicationId,
      responsibleIndividualName: String,
      responsibleIndividualEmail: LaxEmailAddress,
      eventDateTime: LocalDateTime,
      submissionId: SubmissionId,
      submissionIndex: Int
    ): Future[ApplicationData] =
    updateApplication(
      applicationId,
      Updates.combine(
        Updates.push(
          "access.importantSubmissionData.termsOfUseAcceptances",
          Codecs.toBson(TermsOfUseAcceptance(
            ResponsibleIndividual.build(responsibleIndividualName, responsibleIndividualEmail.text),
            eventDateTime,
            submissionId,
            submissionIndex
          ))
        )
      )
    )

  def updateApplicationState(
      applicationId: ApplicationId,
      newAppState: State,
      timestamp: LocalDateTime,
      requestingAdminEmail: String,
      requestingAdminName: String
    ): Future[ApplicationData] =
    updateApplication(
      applicationId,
      Updates.combine(
        Updates.set("state.name", Codecs.toBson(newAppState)),
        Updates.set("state.updatedOn", timestamp),
        Updates.set("state.requestedByEmailAddress", requestingAdminEmail),
        Updates.set("state.requestedByName", requestingAdminName)
      )
    )

  def getAppsWithSubscriptions: Future[List[ApplicationWithSubscriptions]] = {
    timeFuture("Applications with Subscription", "application.repository.getAppsWithSubscriptions") {
      val pipeline = Seq(
        lookup(from = "subscription", localField = "id", foreignField = "applications", as = "subscribedApis"),
        unwind("$subscribedApis"),
        group(
          Document("id" -> "$id", "name" -> "$name"),
          Accumulators.first("id", "$id"),
          Accumulators.first("name", "$name"),
          Accumulators.first("lastAccess", "$lastAccess"),
          Accumulators.addToSet("apiIdentifiers", "$subscribedApis.apiIdentifier")
        )
      )

      collection.aggregate[BsonValue](pipeline)
        .map(Codecs.fromBson[ApplicationWithSubscriptions])
        .toFuture()
        .map(_.toList)
    }
  }
}
