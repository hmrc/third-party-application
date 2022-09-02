/*
 * Copyright 2022 HM Revenue & Customs
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

import cats.data.NonEmptyList
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
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.AccessType
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{ResponsibleIndividualChanged, ResponsibleIndividualSet, ApplicationStateChanged}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.util.MetricsHelper

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationRepository @Inject() (mongo: MongoComponent)(implicit val ec: ExecutionContext)
    extends PlayMongoRepository[ApplicationData](
      collectionName = "application",
      mongoComponent = mongo,
      domainFormat = ApplicationData.format,
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
        )
      ),
      replaceIndexes = true
    ) with MetricsHelper {

  import MongoJsonFormatterOverrides._

  def save(application: ApplicationData): Future[ApplicationData] = {
    val query = equal("id", Codecs.toBson(application.id))
    collection.find(query).headOption flatMap {
      case Some(_: ApplicationData) =>
        collection.replaceOne(
          filter = query,
          replacement = application
        ).toFuture().map(_ => application)

      case None => collection.insertOne(application).toFuture().map(_ => application)
    }
  }

  def updateApplicationRateLimit(applicationId: ApplicationId, rateLimit: RateLimitTier): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("rateLimitTier", Codecs.toBson(rateLimit)))

  def updateApplicationIpAllowlist(applicationId: ApplicationId, ipAllowlist: IpAllowlist): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("ipAllowlist", Codecs.toBson(ipAllowlist)))

  def updateApplicationGrantLength(applicationId: ApplicationId, grantLength: Int): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("grantLength", grantLength))

  def addApplicationTermsOfUseAcceptance(applicationId: ApplicationId, acceptance: TermsOfUseAcceptance): Future[ApplicationData] =
    updateApplication(applicationId, Updates.push("access.importantSubmissionData.termsOfUseAcceptances", Codecs.toBson(acceptance)))

  def recordApplicationUsage(applicationId: ApplicationId): Future[ApplicationData] =
    updateApplication(applicationId, Updates.currentDate("lastAccess"))

  def recordServerTokenUsage(applicationId: ApplicationId): Future[ApplicationData] =
    updateApplication(applicationId, Updates.combine(Updates.currentDate("lastAccess"), Updates.currentDate("tokens.production.lastAccessTokenUsage")))

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

  def updateClientSecretField(applicationId: ApplicationId, clientSecretId: String, fieldName: String, fieldValue: String): Future[ApplicationData] = {
    val query = and(
      equal("id", Codecs.toBson(applicationId)),
      equal("tokens.production.clientSecrets.id", clientSecretId)
    )

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.set(s"tokens.production.clientSecrets.$$.$fieldName", fieldValue),
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def addClientSecret(applicationId: ApplicationId, clientSecret: ClientSecret): Future[ApplicationData] =
    updateApplication(applicationId, Updates.push("tokens.production.clientSecrets", Codecs.toBson(clientSecret)))

  def updateClientSecretName(applicationId: ApplicationId, clientSecretId: String, newName: String): Future[ApplicationData] =
    updateClientSecretField(applicationId, clientSecretId, "name", newName)

  def updateClientSecretHash(applicationId: ApplicationId, clientSecretId: String, hashedSecret: String): Future[ApplicationData] =
    updateClientSecretField(applicationId, clientSecretId, "hashedSecret", hashedSecret)

  def recordClientSecretUsage(applicationId: ApplicationId, clientSecretId: String): Future[ApplicationData] = {
    val query = and(
      equal("id", Codecs.toBson(applicationId)),
      equal("tokens.production.clientSecrets.id", clientSecretId)
    )

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.currentDate("tokens.production.clientSecrets.$.lastAccess"),
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def deleteClientSecret(applicationId: ApplicationId, clientSecretId: String): Future[ApplicationData] = {
    val query = equal("id", Codecs.toBson(applicationId))

    collection.findOneAndUpdate(
      filter = query,
      update = Updates.pull("tokens.production.clientSecrets", Codecs.toBson(Json.obj("id" -> clientSecretId))),
      options = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).toFuture()
  }

  def fetchStandardNonTestingApps(): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("access.accessType", Codecs.toBson(AccessType.STANDARD)),
      notEqual("state.name", Codecs.toBson(State.TESTING))
    )

    collection.find(query).toFuture()
  }

  def fetch(id: ApplicationId): Future[Option[ApplicationData]] = {
    collection.find(equal("id", Codecs.toBson(id))).headOption()
  }

  def fetchApplicationsByName(name: String): Future[Seq[ApplicationData]] = {
    collection.find(equal("normalisedName", name.toLowerCase)).toFuture()
  }

  def fetchVerifiableUpliftBy(verificationCode: String): Future[Option[ApplicationData]] = {
    collection.find(equal("state.verificationCode", verificationCode)).headOption()
  }

  def fetchAllByStatusDetails(state: State.State, updatedBefore: LocalDateTime): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("state.name", Codecs.toBson(state)),
      lte("state.updatedOn", updatedBefore)
    )

    collection.find(query).toFuture()
  }

  def fetchByClientId(clientId: ClientId): Future[Option[ApplicationData]] = {
    collection.find(equal("tokens.production.clientId", Codecs.toBson(clientId))).headOption()
  }

  def fetchByServerToken(serverToken: String): Future[Option[ApplicationData]] = {
    collection.find(equal("tokens.production.accessToken", serverToken)).headOption()
  }

  def fetchAllForUserId(userId: UserId): Future[Seq[ApplicationData]] = {
    collection.find(equal("collaborators.userId", Codecs.toBson(userId))).toFuture()
  }

  def fetchAllForUserIdAndEnvironment(userId: UserId, environment: String): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("collaborators.userId", Codecs.toBson(userId)),
      equal("environment", environment)
    )

    collection.find(query).toFuture()
  }

  def fetchAllForEmailAddress(emailAddress: String): Future[Seq[ApplicationData]] = {
    collection.find(equal("collaborators.emailAddress", emailAddress)).toFuture()
  }

  def fetchAllForEmailAddressAndEnvironment(emailAddress: String, environment: String): Future[Seq[ApplicationData]] = {
    val query = and(
      equal("collaborators.emailAddress", emailAddress),
      equal("environment", environment)
    )

    collection.find(query).toFuture()
  }

  def fetchProdAppStateHistories(): Future[Seq[ApplicationWithStateHistory]] = {
    val pipeline: Seq[Bson] = Seq(
      matches(equal("environment", Codecs.toBson(Environment.PRODUCTION))),
      addFields(Field("version", cond(Document("$not" -> BsonString("$access.importantSubmissionData")), 1, 2))),
      lookup(from = "stateHistory", localField = "id", foreignField = "applicationId", as = "states"),
      sort(ascending("createdOn", "states.changedAt"))
    )
    collection.aggregate[BsonValue](pipeline).map(Codecs.fromBson[ApplicationWithStateHistory]).toFuture()
  }

  def searchApplications(applicationSearch: ApplicationSearch): Future[PaginatedApplicationData] = {
    val filters = applicationSearch.filters.map(filter => convertFilterToQueryClause(filter, applicationSearch))
    val sort    = convertToSortClause(applicationSearch.sort)

    val pagination = List(
      skip((applicationSearch.pageNumber - 1) * applicationSearch.pageSize),
      limit(applicationSearch.pageSize)
    )

    runAggregationQuery(filters, pagination, sort, applicationSearch.hasSubscriptionFilter(), applicationSearch.hasSpecificApiSubscriptionFilter())
  }

  private def cond[T](condition: Document, trueValue: Int, falseValue: Int): Bson = {
    Document("$cond" -> BsonArray(
      condition,
      BsonInt32(trueValue),
      BsonInt32(falseValue)
    ))
  }


  private def matches(predicates: Bson): Bson = filter(predicates)

  private def in(fieldName: String, values: Seq[String]): Bson = matches(Filters.in(fieldName, values: _*))

  private def convertFilterToQueryClause(applicationSearchFilter: ApplicationSearchFilter, applicationSearch: ApplicationSearch): Bson = {

    def applicationStatusMatch(states: State*): Bson = in("state.name", states.map(_.toString))

    def accessTypeMatch(accessType: AccessType): Bson = matches(equal("access.accessType", Codecs.toBson(accessType)))

    def specificAPISubscription(apiContext: ApiContext, apiVersion: Option[ApiVersion]) = {
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

      // Access Type
      case StandardAccess   => accessTypeMatch(AccessType.STANDARD)
      case ROPCAccess       => accessTypeMatch(AccessType.ROPC)
      case PrivilegedAccess => accessTypeMatch(AccessType.PRIVILEGED)

      // Text Search
      case ApplicationTextSearch => regexTextSearch(List("id", "name", "tokens.production.clientId"), applicationSearch.textToSearch.getOrElse(""))

      // Last Use Date
      case lastUsedBefore: LastUseBeforeDate => lastUsedBefore.toMongoMatch
      case lastUsedAfter: LastUseAfterDate   => lastUsedAfter.toMongoMatch
      case _                                 => Document() // Only here to complete the match
    }
  }

  private def convertToSortClause(sort: ApplicationSort): List[Bson] = sort match {
    case NameAscending         => List(Aggregates.sort(Sorts.ascending("name")))
    case NameDescending        => List(Aggregates.sort(Sorts.descending("name")))
    case SubmittedAscending    => List(Aggregates.sort(Sorts.ascending("createdOn")))
    case SubmittedDescending   => List(Aggregates.sort(Sorts.descending("createdOn")))
    case LastUseDateAscending  => List(Aggregates.sort(Sorts.ascending("lastAccess")))
    case LastUseDateDescending => List(Aggregates.sort(Sorts.descending("lastAccess")))
    case NoSorting             => List()
    case _                     => List(Aggregates.sort(Sorts.ascending("name")))
  }

  private def regexTextSearch(fields: List[String], searchText: String): Bson = {
    matches(or(fields.map(field => regex(field, searchText, "i")): _*))
  }

  private def runAggregationQuery(filters: List[Bson], pagination: List[Bson], sort: List[Bson], hasSubscriptionsQuery: Boolean, hasSpecificApiSubscription: Boolean) = {
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
        "environment"
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

  def fetchAllForContext(apiContext: ApiContext): Future[List[ApplicationData]] =
    searchApplications(
      ApplicationSearch(
        filters = List(SpecificAPISubscription),
        apiContext = Some(apiContext)
      )
    ).map(_.applications)

  def fetchAllForApiIdentifier(apiIdentifier: ApiIdentifier): Future[List[ApplicationData]] =
    searchApplications(
      ApplicationSearch(
        filters = List(SpecificAPISubscription),
        apiContext = Some(apiIdentifier.context),
        apiVersion = Some(apiIdentifier.version)
      )
    ).map(_.applications)

  def fetchAllWithNoSubscriptions(): Future[List[ApplicationData]] =
    searchApplications(new ApplicationSearch(filters = List(NoAPISubscriptions))).map(_.applications)

  def fetchAll(): Future[List[ApplicationData]] = {
    val result = searchApplications(new ApplicationSearch())

    result.map(_.applications)
  }

  def processAll(function: ApplicationData => Unit): Future[Unit] = {
    collection.find()
      .map(function)
      .toFuture()
      .map(_ => ())
  }

  def delete(id: ApplicationId): Future[HasSucceeded] = {
    collection.deleteOne(equal("id", Codecs.toBson(id)))
      .toFuture()
      .map(_ => HasSucceeded)
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
    val pipeline = Seq(
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

  def applyEvents(events: NonEmptyList[UpdateApplicationEvent]): Future[ApplicationData] = {
    require(events.map(_.applicationId).toList.toSet.size == 1, "Events must all be for the same application")

    events match {
      case NonEmptyList(e, Nil)  => applyEvent(e)
      case NonEmptyList(e, tail) => applyEvent(e).flatMap(_ => applyEvents(NonEmptyList.fromListUnsafe(tail)))
    }
  }

  private def updateApplicationName(applicationId: ApplicationId, name: String): Future[ApplicationData] =
    updateApplication(applicationId, Updates.combine(
      Updates.set("name", name),
      Updates.set("normalisedName", name.toLowerCase)
    ))

  private def updateApplicationPrivacyPolicyLocation(applicationId: ApplicationId, location: PrivacyPolicyLocation): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.importantSubmissionData.privacyPolicyLocation", Codecs.toBson(location)))

  private def updateLegacyApplicationPrivacyPolicyLocation(applicationId: ApplicationId, url: String): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.privacyPolicyUrl", url))

  private def updateApplicationTermsAndConditionsLocation(applicationId: ApplicationId, location: TermsAndConditionsLocation): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.importantSubmissionData.termsAndConditionsLocation", Codecs.toBson(location)))

  private def updateLegacyApplicationTermsAndConditionsLocation(applicationId: ApplicationId, url: String): Future[ApplicationData] =
    updateApplication(applicationId, Updates.set("access.termsAndConditionsUrl", url))

  private def updateApplicationChangeResponsibleIndividual(event: ResponsibleIndividualChanged): Future[ApplicationData] =
    updateApplication(event.applicationId, Updates.combine(
      Updates.set("access.importantSubmissionData.responsibleIndividual.fullName", event.newResponsibleIndividualName),
      Updates.set("access.importantSubmissionData.responsibleIndividual.emailAddress", event.newResponsibleIndividualEmail),
      Updates.push("access.importantSubmissionData.termsOfUseAcceptances", Codecs.toBson(TermsOfUseAcceptance(
        ResponsibleIndividual.build(event.newResponsibleIndividualName, event.newResponsibleIndividualEmail),
        event.eventDateTime,
        event.submissionId,
        event.submissionIndex
      )))
    ))

  private def updateApplicationSetResponsibleIndividual(event: ResponsibleIndividualSet): Future[ApplicationData] =
    updateApplication(event.applicationId, Updates.combine(
      Updates.push("access.importantSubmissionData.termsOfUseAcceptances", Codecs.toBson(TermsOfUseAcceptance(
        ResponsibleIndividual.build(event.responsibleIndividualName, event.responsibleIndividualEmail),
        event.eventDateTime,
        event.submissionId,
        event.submissionIndex
      )))
    ))
  
  private def updateApplicationState(event: ApplicationStateChanged): Future[ApplicationData] =
    updateApplication(event.applicationId, Updates.combine(
      Updates.set("state.name", Codecs.toBson(event.newAppState)),
      Updates.set("state.updatedOn", event.eventDateTime),
      Updates.set("state.requestedByEmailAddress", event.requestingAdminEmail),
      Updates.set("state.requestedByName", event.requestingAdminName)
    ))

  private def noOp(event: UpdateApplicationEvent): Future[ApplicationData] = fetch(event.applicationId).map(_.get)

  private def applyEvent(event: UpdateApplicationEvent): Future[ApplicationData] = {
    import UpdateApplicationEvent._

    event match {
      case evt : ProductionAppNameChanged => updateApplicationName(evt.applicationId, evt.newAppName)
      case evt : ProductionAppPrivacyPolicyLocationChanged => updateApplicationPrivacyPolicyLocation(evt.applicationId, evt.newLocation)
      case evt : ProductionLegacyAppPrivacyPolicyLocationChanged => updateLegacyApplicationPrivacyPolicyLocation(evt.applicationId, evt.newUrl)
      case evt : ProductionAppTermsConditionsLocationChanged => updateApplicationTermsAndConditionsLocation(evt.applicationId, evt.newLocation)
      case evt : ProductionLegacyAppTermsConditionsLocationChanged => updateLegacyApplicationTermsAndConditionsLocation(evt.applicationId, evt.newUrl)
      case evt : ResponsibleIndividualSet => updateApplicationSetResponsibleIndividual(evt)
      case evt : ResponsibleIndividualChanged => updateApplicationChangeResponsibleIndividual(evt)
      case evt : ApplicationStateChanged => updateApplicationState(evt)
      case _ : ResponsibleIndividualVerificationStarted => noOp(event)
      case _ : ResponsibleIndividualVerificationCompleted => noOp(event)
    }
  }
}

sealed trait ApplicationModificationResult

final case class SuccessfulApplicationModificationResult(numberOfDocumentsUpdated: Int) extends ApplicationModificationResult

final case class UnsuccessfulApplicationModificationResult(message: Option[String]) extends ApplicationModificationResult
