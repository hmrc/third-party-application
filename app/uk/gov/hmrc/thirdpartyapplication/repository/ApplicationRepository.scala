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

import java.time.{Clock, Instant, Period}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats.syntax.option._
import com.mongodb.client.model.{FindOneAndUpdateOptions, ReturnDocument}
import com.typesafe.config.ConfigFactory
import org.bson.BsonValue
import org.bson.conversions.Bson
import org.mongodb.scala.bson._
import org.mongodb.scala.model
import org.mongodb.scala.model.Aggregates._
import org.mongodb.scala.model.Filters.{exists, _}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.Projections.{computed, excludeId, fields, include}
import org.mongodb.scala.model._

import play.api.libs.json.Json._
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, AccessType, OverrideFlag, SellResellOrDistribute}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.QueriedApplication
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.{SingleApplicationQuery, _}
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models.{ApiFieldMap, FieldValue}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util.MetricsTimer

object ApplicationRepository {
  import play.api.libs.functional.syntax._

  val grantLengthConfig = ConfigFactory.load().getInt("grantLengthInDays")

  protected case class QueriedStoredApplication(
      app: StoredApplication,
      subscriptions: Option[Set[ApiIdentifier]] = None,
      fieldValues: Option[ApiFieldMap[FieldValue]] = None,
      stateHistory: Option[List[StateHistory]] = None
    ) {

    def asQueriedApplication = {
      val awc = app.asAppWithCollaborators
      QueriedApplication(
        details = awc.details,
        collaborators = awc.collaborators,
        subscriptions = subscriptions,
        fieldValues = fieldValues,
        stateHistory = stateHistory
      )
    }
  }

  object MongoFormats {
    import uk.gov.hmrc.play.json.Union

    implicit val formatInstant: Format[Instant] = MongoJavatimeFormats.instantFormat

    implicit val formatTermsOfUseAcceptance: OFormat[TermsOfUseAcceptance]       = Json.using[Json.WithDefaultValues].format[TermsOfUseAcceptance]
    implicit val formatTermsOfUserAgreement: OFormat[TermsOfUseAgreement]        = Json.format[TermsOfUseAgreement]
    implicit val formatImportantSubmissionData: OFormat[ImportantSubmissionData] = Json.format[ImportantSubmissionData]
    implicit val formatStateHistory: OFormat[StateHistory]                       = Json.format[StateHistory]

    implicit val formatStoredClientSecret: OFormat[StoredClientSecret] = Json.format[StoredClientSecret]
    implicit val formatStoredToken: OFormat[StoredToken]               = Json.format[StoredToken]

    implicit val writesStandard: OWrites[Access.Standard] = Json.writes[Access.Standard]

    // Because the data in the db might be old and not a valid redirect URI we need this
    // to use new to avoid the filter on RedirectUri.apply()
    private val convertToLoginRedirectUri: List[String] => List[LoginRedirectUri] = items =>
      items.map(new LoginRedirectUri(_))

    implicit val readsStandard: Reads[Access.Standard] = (
      ((JsPath \ "redirectUris").read[List[String]].map[List[LoginRedirectUri]](convertToLoginRedirectUri)) and
        ((JsPath \ "postLogoutRedirectUris").read[List[PostLogoutRedirectUri]] or Reads.pure(List.empty[PostLogoutRedirectUri])) and
        (JsPath \ "termsAndConditionsUrl").readNullable[String] and
        (JsPath \ "privacyPolicyUrl").readNullable[String] and
        (JsPath \ "overrides").read[Set[OverrideFlag]] and
        (JsPath \ "sellResellOrDistribute").readNullable[SellResellOrDistribute] and
        (JsPath \ "importantSubmissionData").readNullable[ImportantSubmissionData]
    )(Access.Standard.apply _)

    implicit val formatPrivileged: OFormat[Access.Privileged] = Json.format[Access.Privileged]
    implicit val formatRopc: OFormat[Access.Ropc]             = Json.format[Access.Ropc]

    implicit val formatAccess: OFormat[Access] = Union.from[Access]("accessType")
      .and[Access.Standard](AccessType.STANDARD.toString)
      .and[Access.Privileged](AccessType.PRIVILEGED.toString)
      .and[Access.Ropc](AccessType.ROPC.toString)
      .format

    private val readsCheckInformation: Reads[CheckInformation] = (
      (JsPath \ "contactDetails").readNullable[ContactDetails] and
        (JsPath \ "confirmedName").read[Boolean] and
        ((JsPath \ "apiSubscriptionsConfirmed").read[Boolean] or Reads.pure(false)) and
        ((JsPath \ "apiSubscriptionConfigurationsConfirmed").read[Boolean] or Reads.pure(false)) and
        (JsPath \ "providedPrivacyPolicyURL").read[Boolean] and
        (JsPath \ "providedTermsAndConditionsURL").read[Boolean] and
        (JsPath \ "applicationDetails").readNullable[String] and
        ((JsPath \ "teamConfirmed").read[Boolean] or Reads.pure(false)) and
        ((JsPath \ "termsOfUseAgreements").read[List[TermsOfUseAgreement]] or Reads.pure(List.empty[TermsOfUseAgreement]))
    )(CheckInformation.apply _)

    implicit val formatCheckInformation: Format[CheckInformation] = Format(readsCheckInformation, Json.writes[CheckInformation])

    implicit val formatApplicationState: OFormat[ApplicationState]   = Json.format[ApplicationState]
    implicit val formatApplicationTokens: OFormat[ApplicationTokens] = Json.format[ApplicationTokens]

    // Non-standard format compared to companion object
    val ipAllowlistReads: Reads[IpAllowlist]             = (
      ((JsPath \ "required").read[Boolean] or Reads.pure(false)) and
        ((JsPath \ "allowlist").read[Set[String]] or Reads.pure(Set.empty[String]))
    )(IpAllowlist.apply _)
    implicit val formatIpAllowlist: OFormat[IpAllowlist] = OFormat(ipAllowlistReads, Json.writes[IpAllowlist])

    def periodFromInt(i: Int): Period = (GrantLength.apply(i).getOrElse(GrantLength.EIGHTEEN_MONTHS)).period

    // Non-standard format compared to companion object
    val readStoredApplication: Reads[StoredApplication] = (
      (JsPath \ "id").read[ApplicationId] and
        (JsPath \ "name").read[ApplicationName] and
        (JsPath \ "normalisedName").read[String] and
        (JsPath \ "collaborators").read[Set[Collaborator]] and
        (JsPath \ "description").readNullable[String] and
        (JsPath \ "wso2ApplicationName").read[String] and
        (JsPath \ "tokens").read[ApplicationTokens] and
        (JsPath \ "state").read[ApplicationState] and
        (JsPath \ "access").read[Access] and
        (JsPath \ "createdOn").read[Instant] and
        (JsPath \ "lastAccess").readNullable[Instant] and
        (((JsPath \ "refreshTokensAvailableFor").read[Period]
          .orElse((JsPath \ "grantLength").read[Int].map(periodFromInt(_))))
          or Reads.pure(periodFromInt(grantLengthConfig))) and
        (JsPath \ "rateLimitTier").readNullable[RateLimitTier] and
        (JsPath \ "environment").read[Environment] and
        (JsPath \ "checkInformation").readNullable[CheckInformation] and
        ((JsPath \ "blocked").read[Boolean] or Reads.pure(false)) and
        ((JsPath \ "ipAllowlist").read[IpAllowlist] or Reads.pure(IpAllowlist())) and
        ((JsPath \ "deleteRestriction").read[DeleteRestriction] or Reads.pure[DeleteRestriction](DeleteRestriction.NoRestriction)) and
        (JsPath \ "organisationId").readNullable[OrganisationId]
    )(StoredApplication.apply _)

    implicit val formatStoredApplication: OFormat[StoredApplication] = OFormat(readStoredApplication, Json.writes[StoredApplication])

    implicit val formatApplicationWithStateHistory: OFormat[ApplicationWithStateHistory]        = Json.format[ApplicationWithStateHistory]
    implicit val readsApplicationWithSubscriptionCount: Reads[ApplicationWithSubscriptionCount] = Json.reads[ApplicationWithSubscriptionCount]

    case class PaginationTotal(total: Int)
    case class PaginatedApplicationData(applications: List[StoredApplication], countOfAllApps: List[PaginationTotal], countOfMatchingApps: List[PaginationTotal])

    implicit val formatPaginationTotal: Format[PaginationTotal]                 = Json.format[PaginationTotal]
    implicit val readsPaginatedApplicationData: Reads[PaginatedApplicationData] = Json.reads[PaginatedApplicationData]
    implicit val readsQSA: Reads[QueriedStoredApplication]                      = Json.reads[QueriedStoredApplication]
  }
}

@Singleton
class ApplicationRepository @Inject() (mongo: MongoComponent, val metrics: Metrics, val clock: Clock)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[StoredApplication](
      collectionName = "application",
      mongoComponent = mongo,
      domainFormat = ApplicationRepository.MongoFormats.formatStoredApplication,
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
      extraCodecs = Seq(
        Codecs.playFormatCodec(LaxEmailAddress.format),
        Codecs.playFormatCodec(ClientId.format)
      )
    ) with MetricsTimer
    with ApplicationLogger
    with ClockNow {

  import ApplicationRepository.MongoFormats._
  import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository.QueriedStoredApplication

  def save(application: StoredApplication): Future[StoredApplication] = {
    val query = equal("id", Codecs.toBson(application.id))
    collection.find(query).headOption().flatMap {
      case Some(_: StoredApplication) =>
        collection.replaceOne(
          filter = query,
          replacement = application
        ).toFuture().map(_ => application)

      case None =>
        collection.insertOne(application).toFuture().map(_ => application)
    }
  }

  def updateDeleteRestriction(applicationId: ApplicationId, deleteRestriction: DeleteRestriction): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("deleteRestriction", Codecs.toBson(deleteRestriction)))

  def updateApplicationRateLimit(applicationId: ApplicationId, rateLimit: RateLimitTier): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("rateLimitTier", Codecs.toBson(rateLimit)))

  def updateApplicationIpAllowlist(applicationId: ApplicationId, ipAllowlist: IpAllowlist): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("ipAllowlist", Codecs.toBson(ipAllowlist)))

  def updateApplicationGrantLength(applicationId: ApplicationId, refreshTokensAvailableFor: Period): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("refreshTokensAvailableFor", Codecs.toBson(refreshTokensAvailableFor)))

  def updateApplicationImportantSubmissionData(applicationId: ApplicationId, importantSubmissionData: ImportantSubmissionData): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("access.importantSubmissionData", Codecs.toBson(importantSubmissionData)))

  def removeOldGrantLength(applicationId: ApplicationId): Future[StoredApplication] =
    updateApplication(applicationId, Updates.unset("grantLength"))

  def addApplicationTermsOfUseAcceptance(applicationId: ApplicationId, acceptance: TermsOfUseAcceptance): Future[StoredApplication] =
    updateApplication(applicationId, Updates.push("access.importantSubmissionData.termsOfUseAcceptances", Codecs.toBson(acceptance)))

  def findAndRecordApplicationUsage(clientId: ClientId): Future[Option[StoredApplication]] = {
    // For startDate calculation, ifNull provides a default date when lastAccess is not yet set
    timeFuture("Find and Record Application Usage", "application.repository.findAndRecordApplicationUsage") {
      val timeOfAccess    = instant().toString
      // lastAccess is set to the same as createdOn when a new application is created
      val aggregateUpdate = Seq(BsonDocument(
        s"""{
          $$set: { 
            "lastAccess": {
              $$cond: {
                if: {
                  $$or: [
                    {
                      $$eq: [ "$$lastAccess", "$$createdOn" ]
                    },
                    {
                      $$gt: [
                        {
                          $$dateDiff: {
                            startDate: { $$ifNull: [ "$$lastAccess", ISODate("1970-01-01T00:00:00.000Z") ] },
                            endDate: ISODate("$timeOfAccess"),
                            unit: "day"
                          }
                        },
                        0
                      ]
                    }
                  ]
                }
                then: ISODate("$timeOfAccess"),
                else: "$$lastAccess"
              }
            }
          } 
        }"""
      ))

      val query = and(
        equal("tokens.production.clientId", clientId),
        notEqual("state.name", State.DELETED.toString())
      )

      val options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)

      collection.findOneAndUpdate(query, aggregateUpdate, options).headOption()
    }
  }

  def findAndRecordServerTokenUsage(serverToken: String): Future[Option[StoredApplication]] = {
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
  def updateCollaboratorId(applicationId: ApplicationId, collaboratorEmailAddress: String, collaboratorUser: UserId): Future[Option[StoredApplication]] = {
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

  def updateApplication(applicationId: ApplicationId, updateStatement: Bson): Future[StoredApplication] = {
    val query = equal("id", Codecs.toBson(applicationId))

    collection.findOneAndUpdate(
      filter = query,
      update = updateStatement,
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def updateClientSecretField(applicationId: ApplicationId, clientSecretId: ClientSecret.Id, fieldName: String, fieldValue: String): Future[StoredApplication] = {
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

  def addClientSecret(applicationId: ApplicationId, clientSecret: StoredClientSecret): Future[StoredApplication] =
    updateApplication(applicationId, Updates.push("tokens.production.clientSecrets", Codecs.toBson(clientSecret)))

  def updateClientSecretName(applicationId: ApplicationId, clientSecretId: ClientSecret.Id, newName: String): Future[StoredApplication] =
    updateClientSecretField(applicationId, clientSecretId, "name", newName)

  def updateClientSecretHash(applicationId: ApplicationId, clientSecretId: ClientSecret.Id, hashedSecret: String): Future[StoredApplication] =
    updateClientSecretField(applicationId, clientSecretId, "hashedSecret", hashedSecret)

  def recordClientSecretUsage(applicationId: ApplicationId, clientSecretId: ClientSecret.Id): Future[StoredApplication] = {
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

  def deleteClientSecret(applicationId: ApplicationId, clientSecretId: ClientSecret.Id): Future[StoredApplication] = {
    val query = equal("id", Codecs.toBson(applicationId))

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.pull("tokens.production.clientSecrets", Codecs.toBson(Json.obj("id" -> clientSecretId))),
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def fetch(id: ApplicationId): Future[Option[StoredApplication]] = {
    fetchStoredApplication(ApplicationQuery.ById(id, List.empty))
  }

  // TODO - definitely
  def fetchByStatusDetailsAndEnvironmentForDeleteJob(state: State, updatedBefore: Instant, environment: Environment): Future[Seq[StoredApplication]] = {
    collection.aggregate(
      Seq(
        filter(equal("state.name", state.toString())),
        filter(equal("environment", Codecs.toBson(environment))),
        filter(notEqual("deleteRestriction.deleteRestrictionType", DeleteRestrictionType.DO_NOT_DELETE.toString())),
        filter(lte("state.updatedOn", updatedBefore))
      )
    ).toFuture()
  }

  // TODO - maybe
  def fetchByStatusDetailsAndEnvironmentNotAleadyNotifiedForDeleteJob(
      state: State,
      updatedBefore: Instant,
      environment: Environment
    ): Future[Seq[StoredApplication]] = {
    timeFuture(
      "Fetch Applications by Status Details and Environment not Already Notified",
      "application.repository.fetchByStatusDetailsAndEnvironmentNotAleadyNotified"
    ) {
      collection.aggregate(
        Seq(
          filter(equal("state.name", state.toString())),
          filter(lte("state.updatedOn", updatedBefore)),
          filter(equal("environment", Codecs.toBson(environment))),
          filter(notEqual("deleteRestriction.deleteRestrictionType", DeleteRestrictionType.DO_NOT_DELETE.toString())),
          lookup(from = "notifications", localField = "id", foreignField = "applicationId", as = "matched"),
          filter(size("matched", 0))
        )
      ).toFuture()
    }
  }

  // TODO - maybe
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

  def fetchProdAppStateHistories(): Future[Seq[ApplicationWithStateHistory]] = {
    def conditional[T](condition: Document, trueValue: Int, falseValue: Int): Bson = {
      Document("$cond" -> BsonArray(
        condition,
        BsonInt32(trueValue),
        BsonInt32(falseValue)
      ))
    }

    timeFuture("Fetch Production Application State Histories", "application.repository.fetchProdAppStateHistories") {
      val pipeline: Seq[Bson] = Seq(
        matches(equal("environment", Codecs.toBson(Environment.PRODUCTION.toString()))),
        matches(notEqual("state.name", State.DELETED.toString())),
        addFields(Field("version", conditional(Document("$not" -> BsonString("$access.importantSubmissionData")), 1, 2))),
        lookup(from = "stateHistory", localField = "id", foreignField = "applicationId", as = "states"),
        sort(ascending("createdOn", "states.changedAt"))
      )
      collection.aggregate[BsonValue](pipeline).map(Codecs.fromBson[ApplicationWithStateHistory]).toFuture()
    }
  }

  def searchApplications(actionSubtask: String)(applicationSearch: ApplicationSearch): Future[PaginatedApplications] = {
    timeFuture("Search Applications", s"application.repository.searchApplications.$actionSubtask") {
      val filters = applicationSearch.filters
        .filterNot(_ == StatusFilter.NoFiltering)
        .map(filter =>
          convertFilterToQueryClause(filter, applicationSearch)
        ) ++ deletedFilter(applicationSearch)

      val sort = convertToSortClause(applicationSearch.sort)

      val pagination = List(
        skip((applicationSearch.pageNumber - 1) * applicationSearch.pageSize),
        limit(applicationSearch.pageSize)
      )

      runAggregationQuery(filters, pagination, sort, applicationSearch.hasSubscriptionFilter(), applicationSearch.hasSpecificApiSubscriptionFilter())
        .map(convertRawData(Pagination(applicationSearch.pageSize, applicationSearch.pageNumber)))
    }
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

    def applicationBlocked(): Bson = matches(and(equal("blocked", BsonBoolean.apply(true)), notEqual("state.name", State.DELETED.toString)))

    def applicationStatusMatch(states: State*): Bson = in("state.name", states.map(_.toString))

    def applicationStatusNotEqual(state: State): Bson = matches(notEqual("state.name", state.toString()))

    def accessTypeMatch(accessType: AccessType): Bson = matches(equal("access.accessType", Codecs.toBson(accessType)))

    def deleteRestrictionMatch(restrictionType: DeleteRestrictionType): Bson = {
      restrictionType match {
        case DeleteRestrictionType.DO_NOT_DELETE  => matches(equal("deleteRestriction.deleteRestrictionType", Codecs.toBson(restrictionType)))
        case DeleteRestrictionType.NO_RESTRICTION =>
          matches(or(equal("deleteRestriction.deleteRestrictionType", Codecs.toBson(restrictionType)), exists("deleteRestriction.deleteRestrictionType", false)))
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
      case APISubscriptionFilter.NoAPISubscriptions        => matches(size("subscribedApis", 0))
      case APISubscriptionFilter.OneOrMoreAPISubscriptions => matches(Document(s"""{$$expr: {$$gte: [{$$size:"$$subscribedApis"}, 1] }}"""))
      case APISubscriptionFilter.SpecificAPISubscription   => specificAPISubscription(applicationSearch.apiContext.get, applicationSearch.apiVersion)

      // Application Status
      case StatusFilter.Created                                  => applicationStatusMatch(State.TESTING)
      case StatusFilter.PendingResponsibleIndividualVerification => applicationStatusMatch(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION)
      case StatusFilter.PendingGatekeeperCheck                   => applicationStatusMatch(State.PENDING_GATEKEEPER_APPROVAL)
      case StatusFilter.PendingSubmitterVerification             => applicationStatusMatch(State.PENDING_REQUESTER_VERIFICATION)
      case StatusFilter.Active                                   => applicationStatusMatch(State.PRE_PRODUCTION, State.PRODUCTION)
      case StatusFilter.WasDeleted                               => applicationStatusMatch(State.DELETED)
      case StatusFilter.ExcludingDeleted                         => applicationStatusNotEqual(State.DELETED)
      case StatusFilter.Blocked                                  => applicationBlocked()

      // Access Type
      case AccessTypeFilter.StandardAccess   => accessTypeMatch(AccessType.STANDARD)
      case AccessTypeFilter.ROPCAccess       => accessTypeMatch(AccessType.ROPC)
      case AccessTypeFilter.PrivilegedAccess => accessTypeMatch(AccessType.PRIVILEGED)

      // Text Search
      case ApplicationTextSearch => regexTextSearch(List("id", "name", "tokens.production.clientId"), applicationSearch.textToSearch.getOrElse(""))

      // Last Use Date
      case lastUsedBefore: LastUseBeforeDate => lastUsedBefore.toMongoMatch
      case lastUsedAfter: LastUseAfterDate   => lastUsedAfter.toMongoMatch

      // Delete Restriction
      case DeleteRestrictionFilter.NoRestriction => deleteRestrictionMatch(DeleteRestrictionType.NO_RESTRICTION)
      case DeleteRestrictionFilter.DoNotDelete   => deleteRestrictionMatch(DeleteRestrictionType.DO_NOT_DELETE)

      case x => throw new RuntimeException(s"There is an umatched query filter (${x.toString()}) that would cause Mongo to fail")
    }
  }
  // scalastyle:on cyclomatic.complexity

  private def convertToSortClause(sort: ApplicationSort): List[Bson] = sort match {
    case ApplicationSort.NameAscending         => List(Aggregates.sort(Sorts.ascending("normalisedName")))
    case ApplicationSort.NameDescending        => List(Aggregates.sort(Sorts.descending("normalisedName")))
    case ApplicationSort.SubmittedAscending    => List(Aggregates.sort(Sorts.ascending("createdOn")))
    case ApplicationSort.SubmittedDescending   => List(Aggregates.sort(Sorts.descending("createdOn")))
    case ApplicationSort.LastUseDateAscending  => List(Aggregates.sort(Sorts.ascending("lastAccess")))
    case ApplicationSort.LastUseDateDescending => List(Aggregates.sort(Sorts.descending("lastAccess")))
    case ApplicationSort.NoSorting             => List()
    case _                                     => List(Aggregates.sort(Sorts.ascending("normalisedName")))
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
          "grantLength",
          "refreshTokensAvailableFor",
          "rateLimitTier",
          "environment",
          "checkInformation",
          "blocked",
          "ipAllowlist",
          "deleteRestriction"
        )
      ))

      val totalCount                                    = Aggregates.count("total")
      val subscriptionsLookupFilter                     = if (hasSubscriptionsQuery) Seq(subscriptionsLookup) else Seq.empty
      val subscriptionsLookupExtendedFilter             = if (hasSpecificApiSubscription) subscriptionsLookupFilter :+ unwindSubscribedApis else subscriptionsLookupFilter
      val filteredPipelineCount                         = subscriptionsLookupExtendedFilter ++ filters :+ totalCount
      val paginatedFilteredAndSortedPipeline: Seq[Bson] = subscriptionsLookupExtendedFilter ++ filters ++ sort ++ pagination :+ applicationProjection

      val facets: Seq[Bson] = Seq(
        facet(
          model.Facet("countOfAllApps", totalCount),
          model.Facet("countOfMatchingApps", filteredPipelineCount: _*),
          model.Facet("applications", paginatedFilteredAndSortedPipeline: _*)
        )
      )

      collection.aggregate[BsonValue](facets)
        .head()
        .map(Codecs.fromBson[PaginatedApplicationData])
        .map(d => PaginatedApplicationData(d.applications, d.countOfAllApps, d.countOfMatchingApps))
    }
  }

  def processAll(function: StoredApplication => Unit): Future[Unit] = {
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

  def delete(id: ApplicationId, updatedOn: Instant): Future[StoredApplication] = {
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

  // TODO - probably
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

  def updateDescription(applicationId: ApplicationId, description: Option[String]) =
    updateApplication(applicationId, Updates.set("description", Codecs.toBson(description.filterNot(_.isBlank()))))

  def updateLegacyPrivacyPolicyUrl(applicationId: ApplicationId, privacyPolicyUrl: Option[String]) = {
    updateApplication(applicationId, Updates.set("access.privacyPolicyUrl", Codecs.toBson(privacyPolicyUrl.filterNot(_.isBlank()))))
  }

  def updateLoginRedirectUris(applicationId: ApplicationId, redirectUris: List[LoginRedirectUri]) =
    updateApplication(applicationId, Updates.set("access.redirectUris", Codecs.toBson(redirectUris)))

  def updatePostLogoutRedirectUris(applicationId: ApplicationId, redirectUris: List[PostLogoutRedirectUri]) =
    updateApplication(applicationId, Updates.set("access.postLogoutRedirectUris", Codecs.toBson(redirectUris)))

  def updateApplicationName(applicationId: ApplicationId, name: String): Future[StoredApplication] =
    updateApplication(
      applicationId,
      Updates.combine(
        Updates.set("name", name),
        Updates.set("normalisedName", name.toLowerCase)
      )
    )

  def updateApplicationPrivacyPolicyLocation(applicationId: ApplicationId, location: PrivacyPolicyLocation): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("access.importantSubmissionData.privacyPolicyLocation", Codecs.toBson(location)))

  def updateApplicationTermsAndConditionsLocation(applicationId: ApplicationId, location: TermsAndConditionsLocation): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("access.importantSubmissionData.termsAndConditionsLocation", Codecs.toBson(location)))

  def updateLegacyTermsAndConditionsUrl(applicationId: ApplicationId, termsAndConditionsUrl: Option[String]): Future[StoredApplication] =
    updateApplication(applicationId, Updates.set("access.termsAndConditionsUrl", Codecs.toBson(termsAndConditionsUrl.filterNot(_.isBlank()))))

  def updateApplicationChangeResponsibleIndividual(
      applicationId: ApplicationId,
      newResponsibleIndividualName: String,
      newResponsibleIndividualEmail: LaxEmailAddress,
      eventDateTime: Instant,
      submissionId: SubmissionId,
      submissionIndex: Int
    ): Future[StoredApplication] = {

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
      timeOfChange: Instant,
      submissionId: SubmissionId,
      submissionIndex: Int
    ): Future[StoredApplication] =
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
      eventDateTime: Instant,
      submissionId: SubmissionId,
      submissionIndex: Int
    ): Future[StoredApplication] =
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
      timestamp: Instant,
      requestingAdminEmail: String,
      requestingAdminName: String
    ): Future[StoredApplication] =
    updateApplication(
      applicationId,
      Updates.combine(
        Updates.set("state.name", Codecs.toBson(newAppState)),
        Updates.set("state.updatedOn", timestamp),
        Updates.set("state.requestedByEmailAddress", requestingAdminEmail),
        Updates.set("state.requestedByName", requestingAdminName)
      )
    )

  // TODO - probably
  def getAppsWithSubscriptions: Future[List[GatekeeperAppSubsResponse]] = {
    implicit val readsGatekeeperAppSubsResponse: Reads[GatekeeperAppSubsResponse] = Json.reads[GatekeeperAppSubsResponse]

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
        .map(Codecs.fromBson[GatekeeperAppSubsResponse])
        .toFuture()
        .map(_.toList)
    }
  }

  import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery._

  private val subscriptionsLookup: Bson =
    lookup(
      from = "subscription",
      as = "subscribedApis",
      localField = "id",
      foreignField = "applications"
    )

  private val stateHistoryLookup: Bson =
    lookup(
      from = "stateHistory",
      as = "stateHistory",
      localField = "id",
      foreignField = "applicationId"
    )

  private val fieldsToProject = List(
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
    "grantLength",
    "refreshTokensAvailableFor",
    "rateLimitTier",
    "environment",
    "checkInformation",
    "blocked",
    "ipAllowlist",
    "deleteRestriction"
  )

  private val transformApplications: Reads[JsArray] = {
    (__ \ "applications").read[JsArray].map { array =>
      JsArray(
        array.value.map { item =>
          val obj              = item.as[JsObject]
          val appFields        = Seq("app" -> (obj - "subscriptions"))
          val oLRSubscriptions = (obj \ "subscriptions")
          val oLRStateHistory  = (obj \ "stateHistory")

          val allFields =
            appFields ++
              (if (oLRSubscriptions.isDefined) Seq(("subscriptions" -> oLRSubscriptions.get)) else Seq.empty) ++
              (if (oLRStateHistory.isDefined) Seq(("stateHistory" -> oLRStateHistory.get)) else Seq.empty)

          JsObject(allFields)
        }
      )
    }
  }

  private def executeAggregate(wantSubscriptions: Boolean, wantStateHistory: Boolean, pipeline: Seq[Bson]): Future[List[QueriedStoredApplication]] = {

    val projectionToUse: Bson =
      project(
        fields(
          (
            Seq(
              excludeId(),
              include(
                fieldsToProject: _*
              )
            ) ++ (
              if (wantSubscriptions)
                Seq(computed("subscriptions", "$subscribedApis.apiIdentifier"))
              else
                Seq.empty
            ) ++ (
              if (wantStateHistory)
                Seq(computed("stateHistory", "$stateHistory"))
              else
                Seq.empty
            )
          ): _*
        )
      )

    val facets: Seq[Bson] = Seq(
      facet(
        model.Facet("applications", (pipeline :+ projectionToUse): _*)
      )
    )

    val listRdr: Reads[List[QueriedStoredApplication]] = implicitly
    implicit val rdr                                   = transformApplications.andThen(listRdr)
    collection.aggregate[BsonValue](facets)
      .head()
      .map(bson => {
        Codecs.fromBson[List[QueriedStoredApplication]](bson)
      })
  }

  protected def fetchSingleAppByAggregates(qry: SingleApplicationQuery): Future[Option[QueriedStoredApplication]] = {
    timeFuture("Run Single Query", "application.repository.fetchSingleAppByAggregates") {
      val filtersStage: List[Bson] = ApplicationQueryConverter.convertToFilter(qry.params)

      val needsLookup         = qry.wantSubscriptions
      val maybeSubsLookup     = subscriptionsLookup.some.filter(_ => needsLookup)
      val pipeline: Seq[Bson] = maybeSubsLookup.toList ++ filtersStage

      executeAggregate(qry.wantSubscriptions, qry.wantStateHistory, pipeline)
        .map(_.headOption)
    }
  }

  // So simple that we don't need to do anything other than use existing methods.  These could be done via conversion to AggregateQuery components at a later date.
  private def internalFetchBySingleApplicationQuery(qry: SingleApplicationQuery): Future[Option[QueriedStoredApplication]] = {
    qry match {
      case ApplicationQuery.ByClientId(clientId, true, _, _, _, _)       =>
        findAndRecordApplicationUsage(clientId).map(osa => osa.map(app => QueriedStoredApplication(app, None, None, None)))
      case ApplicationQuery.ByServerToken(serverToken, true, _, _, _, _) =>
        findAndRecordServerTokenUsage(serverToken).map(osa => osa.map(app => QueriedStoredApplication(app, None, None, None)))
      case _                                                             => fetchSingleAppByAggregates(qry)
    }
  }

  def fetchBySingleApplicationQuery(qry: SingleApplicationQuery): Future[Option[QueriedApplication]] = {
    internalFetchBySingleApplicationQuery(qry).map(_.map(_.asQueriedApplication))
  }

  def fetchStoredApplication(qry: SingleApplicationQuery): Future[Option[StoredApplication]] = {
    internalFetchBySingleApplicationQuery(qry).map(_.map(_.app))
  }

  private def internalFetchByGeneralOpenEndedApplicationQuery(qry: GeneralOpenEndedApplicationQuery): Future[List[QueriedStoredApplication]] = {
    timeFuture("Run General Query", "application.repository.fetchByGeneralOpenEndedApplicationQuery") {
      val filtersStage: List[Bson] = ApplicationQueryConverter.convertToFilter(qry.params)
      val sortingStage: List[Bson] = ApplicationQueryConverter.convertToSort(qry.sorting)

      val needsLookup = qry.wantSubscriptions || qry.hasAnySubscriptionFilter || qry.hasSpecificSubscriptionFilter

      val maybeSubsLookup = subscriptionsLookup.some.filter(_ => needsLookup)
      val maybeSubsUnwind = unwind("$subscribedApis").some.filter(_ => qry.hasSpecificSubscriptionFilter).filterNot(_ => qry.wantSubscriptions)

      val maybeStateHistoryLookup = stateHistoryLookup.some.filter(_ => qry.wantStateHistory)

      val pipeline: Seq[Bson] = maybeSubsLookup.toList ++ maybeStateHistoryLookup.toList ++ maybeSubsUnwind.toList ++ filtersStage ++ sortingStage

      executeAggregate(qry.wantSubscriptions, qry.wantStateHistory, pipeline)
    }
  }

  def fetchByGeneralOpenEndedApplicationQuery(qry: GeneralOpenEndedApplicationQuery): Future[List[QueriedApplication]] = {
    internalFetchByGeneralOpenEndedApplicationQuery(qry).map(_.map(_.asQueriedApplication))
  }

  def fetchStoredApplications(qry: GeneralOpenEndedApplicationQuery): Future[List[StoredApplication]] = {
    internalFetchByGeneralOpenEndedApplicationQuery(qry).map(_.map(_.app))
  }

  def convertRawData(pagination: Pagination)(in: PaginatedApplicationData): PaginatedApplications = {
    PaginatedApplications(
      page = pagination.pageNbr,
      pageSize = pagination.pageSize,
      total = in.countOfAllApps.foldLeft(0)(_ + _.total),
      matching = in.countOfMatchingApps.foldLeft(0)(_ + _.total),
      applications = in.applications.map(_.asAppWithCollaborators)
    )
  }

  def fetchByPaginatedApplicationQuery(qry: PaginatedApplicationQuery): Future[PaginatedApplications] = {

    timeFuture("Run Pagination Query", "application.repository.fetchByPaginatedApplicationQuery") {

      val filtersStage: List[Bson] = ApplicationQueryConverter.convertToFilter(qry.params)
      val sortingStage: List[Bson] = ApplicationQueryConverter.convertToSort(qry.sorting)
      val paginationStage          = List(skip((qry.pagination.pageNbr - 1) * qry.pagination.pageSize), limit(qry.pagination.pageSize))

      val needsLookup = qry.hasAnySubscriptionFilter || qry.hasSpecificSubscriptionFilter

      val maybeSubsLookup = subscriptionsLookup.some.filter(_ => needsLookup)
      val maybeSubsUnwind = unwind("$subscribedApis").some.filter(_ => needsLookup && qry.hasSpecificSubscriptionFilter)

      val projectionToUse =
        project(
          fields(
            excludeId(),
            include(
              fieldsToProject: _*
            )
          )
        )

      val totalCount            = Aggregates.count("total")
      val commonPipeline        = maybeSubsLookup.toList ++ maybeSubsUnwind.toList ++ filtersStage
      val countMatchingPipeline = commonPipeline :+ totalCount
      val pipeline: Seq[Bson]   = commonPipeline ++ sortingStage ++ paginationStage :+ projectionToUse

      val facets: Seq[Bson] = Seq(
        facet(
          model.Facet("countOfAllApps", totalCount),
          model.Facet("countOfMatchingApps", countMatchingPipeline: _*),
          model.Facet("applications", pipeline: _*)
        )
      )

      collection.aggregate[BsonValue](facets)
        .head()
        .map(Codecs.fromBson[PaginatedApplicationData])
        .map(convertRawData(qry.pagination))
    }
  }

  def getAppsForResponsibleIndividualOrAdmin(emailAddress: LaxEmailAddress): Future[List[StoredApplication]] = {
    val query =
      or(
        and(
          elemMatch(
            "collaborators",
            and(
              equal("emailAddress", emailAddress.text),
              equal("role", "ADMINISTRATOR")
            )
          ),
          notEqual("state.name", State.DELETED.toString())
        ),
        and(
          equal("access.importantSubmissionData.responsibleIndividual.emailAddress", emailAddress.text),
          notEqual("state.name", State.DELETED.toString())
        )
      )

    collection.find(query)
      .toFuture()
      .map(_.toList)
  }

}
