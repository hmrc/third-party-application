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

package uk.gov.hmrc.thirdpartyapplication.repository

import java.util.UUID

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.iteratee._
import play.api.libs.json.Json._
import play.api.libs.json.{JsObject, _}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.ReadConcern.Available
import reactivemongo.api.commands.Command.CommandWithPackRunner
import reactivemongo.api.{FailoverStrategy, ReadConcern, ReadPreference}
import reactivemongo.bson.{BSONDateTime, BSONObjectID}
import reactivemongo.play.iteratees.cursorProducer
import reactivemongo.play.json._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.AccessType
import uk.gov.hmrc.thirdpartyapplication.models.MongoFormat._
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.State.State
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.mongo.IndexHelper._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationRepository @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[ApplicationData, BSONObjectID]("application", mongo.mongoConnector.db,
    MongoFormat.formatApplicationData, ReactiveMongoFormats.objectIdFormats) {

  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  private val subscriptionsLookup: JsObject = Json.obj(
    f"$$lookup" -> Json.obj(
      "from" -> "subscription",
      "localField" -> "id",
      "foreignField" -> "applications",
      "as" -> "subscribedApis"))

  private val applicationProjection = Json.obj(f"$$project" -> Json.obj(
    "id" -> true,
    "name" -> true,
    "normalisedName" -> true,
    "collaborators" -> true,
    "description" -> true,
    "wso2Username" -> true,
    "wso2Password" -> true,
    "wso2ApplicationName" -> true,
    "tokens" -> true,
    "state" -> true,
    "access" -> true,
    "createdOn" -> true,
    "lastAccess" -> true,
    "rateLimitTier" -> true,
    "environment" -> true))

  override def indexes = Seq(
    createSingleFieldAscendingIndex(
      indexFieldKey = "state.verificationCode",
      indexName = Some("verificationCodeIndex")
    ),
    createAscendingIndex(
      indexName = Some("stateName_stateUpdatedOn_Index"),
      isUnique = false,
      isBackground = true,
      indexFieldsKey = List("state.name", "state.updatedOn"): _*
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "id",
      indexName = Some("applicationIdIndex"),
      isUnique = true
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "normalisedName",
      indexName = Some("applicationNormalisedNameIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "lastAccess",
      indexName = Some("lastAccessIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "tokens.production.clientId",
      indexName = Some("productionTokenClientIdIndex"),
      isUnique = true
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "access.overrides",
      indexName = Some("accessOverridesIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "access.accessType",
      indexName = Some("accessTypeIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "collaborators.emailAddress",
      indexName = Some("collaboratorsEmailAddressIndex")
    )
  )

  def save(application: ApplicationData): Future[ApplicationData] = {
    findAndUpdate(Json.obj("id" -> application.id.toString), Json.toJson(application).as[JsObject], upsert = true, fetchNewObject = true)
      .map(_.result[ApplicationData].head)
  }

  def updateApplicationRateLimit(applicationId: UUID, rateLimit: RateLimitTier): Future[ApplicationData] =
    updateApplication(applicationId, Json.obj("$set" -> Json.obj("rateLimitTier" -> rateLimit.toString)))

  def recordApplicationUsage(applicationId: UUID): Future[ApplicationData] =
    updateApplication(applicationId, Json.obj("$currentDate" -> Json.obj("lastAccess" -> Json.obj("$type" -> "date"))))

  private def updateApplication(applicationId: UUID, updateStatement: JsObject): Future[ApplicationData] =
    findAndUpdate(Json.obj("id" -> applicationId.toString), updateStatement, fetchNewObject = true) map {
      _.result[ApplicationData].head
    }

  def setMissingLastAccessedDates(dateToSet: DateTime): Future[Int] = {
    def updateApplicationsWithLastAccessDate(applicationIds: Seq[UUID]) = {
      val setLastAccessDate: JsObject = Json.obj("$set" -> Json.obj("lastAccess" -> dateToSet))
      Future.sequence(applicationIds.map(applicationId => findAndUpdate(Json.obj("id" -> applicationId.toString), setLastAccessDate)))
    }

    for {
      applicationIds <- findAll().map(applications => applications.filter(application => application.lastAccess.isEmpty).map(_.id))
      results <- updateApplicationsWithLastAccessDate(applicationIds)
    } yield results.size
  }

  def fetchStandardNonTestingApps(): Future[Seq[ApplicationData]] = {
    find(s"$$and" -> Json.arr(
      Json.obj("state.name" -> Json.obj(f"$$ne" -> State.TESTING)),
      Json.obj("access.accessType" -> Json.obj(f"$$eq" -> AccessType.STANDARD))
    ))
  }

  def fetch(id: UUID): Future[Option[ApplicationData]] = find("id" -> id).map(_.headOption)

  def fetchApplicationsByName(name: String): Future[Seq[ApplicationData]] = {
    val query: (String, JsValueWrapper) = f"$$and" -> Json.arr(
      Json.obj("normalisedName" -> name.toLowerCase)
    )

    find(query)
  }

  def fetchVerifiableUpliftBy(verificationCode: String): Future[Option[ApplicationData]] = {
    find("state.verificationCode" -> verificationCode).map(_.headOption)
  }

  def fetchAllByStatusDetails(state: State.State, updatedBefore: DateTime): Future[Seq[ApplicationData]] = {
    find("state.name" -> state, "state.updatedOn" -> Json.obj(f"$$lte" -> BSONDateTime(updatedBefore.getMillis)))
  }

  def fetchByClientId(clientId: String): Future[Option[ApplicationData]] = {
    find("tokens.production.clientId" -> clientId).map(_.headOption)
  }

  def fetchByServerToken(serverToken: String): Future[Option[ApplicationData]] = {
    find("tokens.production.accessToken" -> serverToken).map(_.headOption)
  }

  def fetchAllForEmailAddress(emailAddress: String): Future[Seq[ApplicationData]] = {
    find("collaborators.emailAddress" -> emailAddress)
  }

  def fetchAllForEmailAddressAndEnvironment(emailAddress: String, environment: String): Future[Seq[ApplicationData]] = {
    find("collaborators.emailAddress" -> emailAddress, "environment" -> environment)
  }

  def searchApplications(applicationSearch: ApplicationSearch): Future[PaginatedApplicationData] = {
    val filters = applicationSearch.filters.map(filter => convertFilterToQueryClause(filter, applicationSearch))
    val sort = Seq(convertToSortClause(applicationSearch.sort))

    val pagination = Seq(
      Json.obj(f"$$skip" -> (applicationSearch.pageNumber - 1) * applicationSearch.pageSize),
      Json.obj(f"$$limit" -> applicationSearch.pageSize))

    runApplicationQueryAggregation(commandQueryDocument(filters, pagination, sort))
  }

  private def matches(predicates: (String, JsValueWrapper)): JsObject = Json.obj(f"$$match" -> Json.obj(predicates))

  private def sorting(clause: (String, JsValueWrapper)): JsObject = Json.obj(f"$$sort" -> Json.obj(clause))

  private def convertFilterToQueryClause(applicationSearchFilter: ApplicationSearchFilter, applicationSearch: ApplicationSearch): JsObject = {
    def applicationStatusMatch(state: State): JsObject = matches("state.name" -> state.toString)

    def accessTypeMatch(accessType: AccessType): JsObject = matches("access.accessType" -> accessType.toString)

    def specificAPISubscription(apiContext: String, apiVersion: String = "") = {
      if (apiVersion.isEmpty) {
        matches("subscribedApis.apiIdentifier.context" -> apiContext)
      } else {
        matches("subscribedApis.apiIdentifier" -> Json.obj("context" -> apiContext, "version" -> apiVersion))
      }
    }

    applicationSearchFilter match {
      // API Subscriptions
      case NoAPISubscriptions => matches("subscribedApis" -> Json.obj(f"$$size" -> 0))
      case OneOrMoreAPISubscriptions => matches("subscribedApis" -> Json.obj(f"$$gt" -> Json.obj(f"$$size" -> 0)))
      case SpecificAPISubscription => specificAPISubscription(applicationSearch.apiContext.getOrElse(""), applicationSearch.apiVersion.getOrElse(""))

      // Application Status
      case Created => applicationStatusMatch(State.TESTING)
      case PendingGatekeeperCheck => applicationStatusMatch(State.PENDING_GATEKEEPER_APPROVAL)
      case PendingSubmitterVerification => applicationStatusMatch(State.PENDING_REQUESTER_VERIFICATION)
      case Active => applicationStatusMatch(State.PRODUCTION)

      // Terms of Use
      case TermsOfUseAccepted => matches("checkInformation.termsOfUseAgreements" -> Json.obj(f"$$gt" -> Json.obj(f"$$size" -> 0)))
      case TermsOfUseNotAccepted =>
        matches(
          f"$$or" ->
            Json.arr(
              Json.obj("checkInformation" -> Json.obj(f"$$exists" -> false)),
              Json.obj("checkInformation.termsOfUseAgreements" -> Json.obj(f"$$exists" -> false)),
              Json.obj("checkInformation.termsOfUseAgreements" -> Json.obj(f"$$size" -> 0))))

      // Access Type
      case StandardAccess => accessTypeMatch(AccessType.STANDARD)
      case ROPCAccess => accessTypeMatch(AccessType.ROPC)
      case PrivilegedAccess => accessTypeMatch(AccessType.PRIVILEGED)

      // Text Search
      case ApplicationTextSearch => regexTextSearch(Seq("id", "name", "tokens.production.clientId"), applicationSearch.textToSearch.getOrElse(""))
    }
  }

  private def convertToSortClause(sort: ApplicationSort): JsObject = sort match {
    case NameAscending => sorting("name" -> 1)
    case NameDescending => sorting("name" -> -1)
    case SubmittedAscending => sorting("createdOn" -> 1)
    case SubmittedDescending => sorting("createdOn" -> -1)
    case _ => sorting("name" -> 1)
  }

  private def regexTextSearch(fields: Seq[String], searchText: String): JsObject =
    matches(f"$$or" -> fields.map(field => Json.obj(field -> Json.obj(f"$$regex" -> searchText, f"$$options" -> "i"))))

  private def runApplicationQueryAggregation(commandDocument: JsObject): Future[PaginatedApplicationData] = {
    val runner = CommandWithPackRunner(JSONSerializationPack, FailoverStrategy())
    runner
      .apply(collection.db, runner.rawCommand(commandDocument))
      .one[JsObject](ReadPreference.nearest)
      .flatMap(processResults[PaginatedApplicationData])
  }

  private def processResults[T](json: JsObject)(implicit fjs: Reads[T]): Future[T] = {
    (json \ "cursor" \ "firstBatch" \ 0).validate[T] match {
      case JsSuccess(result, _) => Future.successful(result)
      case JsError(errors) => Future.failed(new RuntimeException((json \ "errmsg").asOpt[String].getOrElse(errors.mkString(","))))
    }
  }

  private def commandQueryDocument(filters: Seq[JsObject], pagination: Seq[JsObject], sort: Seq[JsObject]): JsObject = {
    val totalCount = Json.arr(Json.obj(f"$$count" -> "total"))
    val filteredPipelineCount = Json.toJson(subscriptionsLookup +: filters :+ Json.obj(f"$$count" -> "total"))
    val paginatedFilteredAndSortedPipeline = Json.toJson((subscriptionsLookup +: filters) ++ sort ++ pagination :+ applicationProjection)

    Json.obj(
      "aggregate" -> "application",
      "cursor" -> Json.obj(),
      "pipeline" -> Json.arr(Json.obj(
        f"$$facet" -> Json.obj(
          "totals" -> totalCount,
          "matching" -> filteredPipelineCount,
          "applications" -> paginatedFilteredAndSortedPipeline))))
  }

  def fetchAllForContext(apiContext: String): Future[Seq[ApplicationData]] =
    searchApplications(ApplicationSearch(1, Int.MaxValue, Seq(SpecificAPISubscription), apiContext = Some(apiContext))).map(_.applications)

  def fetchAllForApiIdentifier(apiIdentifier: APIIdentifier): Future[Seq[ApplicationData]] =
    searchApplications(ApplicationSearch(1, Int.MaxValue, Seq(SpecificAPISubscription), apiContext = Some(apiIdentifier.context),
      apiVersion = Some(apiIdentifier.version))).map(_.applications)

  def fetchAllWithNoSubscriptions(): Future[Seq[ApplicationData]] =
    searchApplications(new ApplicationSearch(filters = Seq(NoAPISubscriptions))).map(_.applications)

  def fetchAll(): Future[Seq[ApplicationData]] = searchApplications(new ApplicationSearch()).map(_.applications)

  def processAll(function: ApplicationData => Unit): Future[Unit] = {
    collection
      .find(Json.obj(), Option.empty[ApplicationData])
      .cursor[ApplicationData]()
      .enumerator()
      .run(Iteratee.foreach(function))
  }

  def delete(id: UUID): Future[HasSucceeded] = {
    Logger.info(s"Pomegranate - In ApplicationRepository.delete() - AppId: $id")

    remove("id" -> id).map(_ => HasSucceeded)
  }

  def documentsWithFieldMissing(fieldName: String): Future[Int] = {
    collection.count(Some(Json.obj(fieldName -> Json.obj(f"$$exists" -> false))), None, 0, None, Available).map(_.toInt)
  }

}

sealed trait ApplicationModificationResult

final case class SuccessfulApplicationModificationResult(numberOfDocumentsUpdated: Int) extends ApplicationModificationResult

final case class UnsuccessfulApplicationModificationResult(message: Option[String]) extends ApplicationModificationResult
