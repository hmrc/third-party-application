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

package uk.gov.hmrc.thirdpartyapplication.services

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import org.joda.time.Duration.standardMinutes
import play.api.Logger
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.{ForbiddenException, HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector.{ApiSubscriptionFieldsConnector, EmailConnector, ThirdPartyDelegatedAuthorityConnector, TotpConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.{AddCollaboratorRequest, AddCollaboratorResponse, DeleteApplicationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.models.ActorType.{COLLABORATOR, GATEKEEPER}
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models.State.{PENDING_GATEKEEPER_APPROVAL, PENDING_REQUESTER_VERIFICATION, State, TESTING}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, _}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, sequence, successful, traverse}
import scala.concurrent.duration.Duration
import scala.util.Failure

@Singleton
class ApplicationService @Inject()(applicationRepository: ApplicationRepository,
                                   stateHistoryRepository: StateHistoryRepository,
                                   subscriptionRepository: SubscriptionRepository,
                                   auditService: AuditService,
                                   emailConnector: EmailConnector,
                                   totpConnector: TotpConnector,
                                   system: ActorSystem,
                                   lockKeeper: ApplicationLockKeeper,
                                   apiGatewayStore: ApiGatewayStore,
                                   applicationResponseCreator: ApplicationResponseCreator,
                                   credentialGenerator: CredentialGenerator,
                                   trustedApplications: TrustedApplications,
                                   apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
                                   thirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector,
                                   nameValidationConfig: ApplicationNameValidationConfig) {


  def create[T <: ApplicationRequest](application: T)(implicit hc: HeaderCarrier): Future[CreateApplicationResponse] = {

    lockKeeper.tryLock {
      createApp(application)
    } flatMap {
      case Some(x) =>
        Logger.info(s"Application ${application.name} has been created successfully")
        Future(x)
      case None =>
        Logger.warn(s"Application creation is locked. Retry scheduled for ${application.name}")
        akka.pattern.after(Duration(3, TimeUnit.SECONDS), using = system.scheduler) {
          create(application)
        }
    }
  }

  def update[T <: ApplicationRequest](id: UUID, application: T)(implicit hc: HeaderCarrier): Future[ApplicationResponse] = {
    updateApp(id)(application) map (app => ApplicationResponse(data = app, trusted = trustedApplications.isTrusted(app)))
  }

  def updateCheck(id: UUID, checkInformation: CheckInformation): Future[ApplicationResponse] = {
    for {
      existing <- fetchApp(id)
      savedApp <- applicationRepository.save(existing.copy(checkInformation = Some(checkInformation)))
    } yield ApplicationResponse(data = savedApp, trusted = trustedApplications.isTrusted(savedApp))
  }

  def addCollaborator(applicationId: UUID, request: AddCollaboratorRequest)(implicit hc: HeaderCarrier) = {

    def validateCollaborator(app: ApplicationData, email: String, role: Role): Collaborator = {
      val normalised = email.toLowerCase
      if (app.collaborators.exists(_.emailAddress == normalised)) throw new UserAlreadyExists

      Collaborator(normalised, role)
    }

    def addUser(app: ApplicationData, collaborator: Collaborator): Future[Set[Collaborator]] = {
      val updated = app.collaborators + collaborator
      applicationRepository.save(app.copy(collaborators = updated)) map (_.collaborators)
    }

    def sendNotificationEmails(applicationName: String, collaborator: Collaborator, registeredUser: Boolean,
                               adminsToEmail: Set[String])(implicit hc: HeaderCarrier): Future[HttpResponse] = {
      def roleForEmail(role: Role) = {
        role match {
          case ADMINISTRATOR => "admin"
          case DEVELOPER => "developer"
          case _ => throw new RuntimeException(s"Unexpected role $role")
        }
      }

      val role: String = roleForEmail(collaborator.role)

      if (adminsToEmail.nonEmpty) {
        emailConnector.sendAddedCollaboratorNotification(collaborator.emailAddress, role, applicationName, adminsToEmail)
      }

      emailConnector.sendAddedCollaboratorConfirmation(role, applicationName, Set(collaborator.emailAddress))
    }

    for {
      app <- fetchApp(applicationId)
      collaborator = validateCollaborator(app, request.collaborator.emailAddress, request.collaborator.role)
      _ <- addUser(app, collaborator)
      _ = auditService.audit(CollaboratorAdded, AuditHelper.applicationId(app.id) ++ CollaboratorAdded.details(collaborator))
      _ = sendNotificationEmails(app.name, collaborator, request.isRegistered, request.adminsToEmail)
    } yield AddCollaboratorResponse(request.isRegistered)
  }

  def updateRateLimitTier(applicationId: UUID, rateLimitTier: RateLimitTier)(implicit hc: HeaderCarrier): Future[ApplicationData] = {

    // NOTE: due to limitations in WSO2 API Manager, all requests to WSO2 Store need to be executed sequentially

    Logger.info(s"Trying to update the rate limit tier to $rateLimitTier for application $applicationId")

    fetchApp(applicationId) flatMap { app =>

      def updateWso2Subscriptions(): Future[Seq[HasSucceeded]] = {
        apiGatewayStore.getSubscriptions(app.wso2Username, app.wso2Password, app.wso2ApplicationName) flatMap { originalApis =>
          sequence(originalApis map { api =>
            apiGatewayStore.resubscribeApi(originalApis, app.wso2Username, app.wso2Password, app.wso2ApplicationName, api, rateLimitTier)
          })
        }
      }

      def updateWso2Application(): Future[HasSucceeded] = {
        for {
          _ <- apiGatewayStore.updateApplication(app, rateLimitTier)
          _ <- apiGatewayStore.checkApplicationRateLimitTier(app.wso2Username, app.wso2Password, app.wso2ApplicationName, rateLimitTier)
        } yield HasSucceeded
      }

      def updateMongoApplication(): Future[ApplicationData] = {
        applicationRepository.save(app.copy(rateLimitTier = Some(rateLimitTier)))
      }

      /*
      NOTE:
      The rate-limit-tier update is not an atomic operation in WSO2 Store.
      The application rate-limit-tier is updated in the Mongo collection only if these steps pass successfully:
       1) the rate-limit-tier is updated in WSO2 Store
       2) all subscriptions are updated (removed and then added back, with the new rate limit tier) in WSO2 Store
      If the rate-limit-tier update fails in WSO2 Store, we need to re-run again the whole rate-limit-tier operation.
      If the subscriptions update fails in WSO2 Store, we need to manually remove and then add back the subscriptions first,
      and then re-run again the whole rate-limit-tier operation.

      There is a scheduled job (RefreshSubscriptionsScheduledJob) that runs automatically.
      This job synchronises the subscriptions from WSO2 Store to Mongo.
      Hence, if the rate-limit-tier operation fails while adding back a subscription in WSO2 Store,
      that subscription will be lost also in Mongo when RefreshSubscriptionsScheduledJob finishes its execution.
      */

      for {
        _ <- updateWso2Application()
        _ <- updateWso2Subscriptions()
        updatedApp <- updateMongoApplication()
      } yield updatedApp
    }

  }

  def deleteApplication(applicationId: UUID, request: Option[DeleteApplicationRequest], auditFunction: ApplicationData => Future[AuditResult])
                       (implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    Logger.info(s"Deleting application $applicationId")

    def deleteSubscriptions(app: ApplicationData): Future[HasSucceeded] = {
      def deleteSubscription(subscription: APIIdentifier) = {
        for {
          _ <- apiGatewayStore.removeSubscription(app, subscription)
          _ <- subscriptionRepository.remove(app.id, subscription)
        } yield HasSucceeded
      }

      for {
        subscriptions <- subscriptionRepository.getSubscriptions(applicationId)
        _ <- traverse(subscriptions)(deleteSubscription)
        _ <- apiSubscriptionFieldsConnector.deleteSubscriptions(app.tokens.production.clientId)
      } yield HasSucceeded
    }

    def sendEmails(app: ApplicationData) = {
      val requesterEmail = request.get.requestedByEmailAddress
      val recipients = app.admins.map(_.emailAddress)
      emailConnector.sendApplicationDeletedNotification(app.name, requesterEmail.toString, recipients)
    }

    (for {
      app <- fetchApp(applicationId)
      _ <- deleteSubscriptions(app)
      _ <- thirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(app.tokens.production.clientId)
      _ <- apiGatewayStore.deleteApplication(app.wso2Username, app.wso2Password, app.wso2ApplicationName)
      _ <- applicationRepository.delete(applicationId)
      _ <- stateHistoryRepository.deleteByApplicationId(applicationId)
      _ = auditFunction(app)
      _ = recoverAll(sendEmails(app))
    } yield Deleted).recover {
      case _: NotFoundException => Deleted
    }
  }

  def deleteCollaborator(applicationId: UUID, collaborator: String, admin: String, adminsToEmail: Set[String])
                        (implicit hc: HeaderCarrier): Future[Set[Collaborator]] = {
    def deleteUser(app: ApplicationData): Future[ApplicationData] = {
      val updatedCollaborators = app.collaborators.filterNot(_.emailAddress equalsIgnoreCase collaborator)
      if (!hasAdmin(updatedCollaborators)) failed(new ApplicationNeedsAdmin)
      else applicationRepository.save(app.copy(collaborators = updatedCollaborators))
    }

    def sendEmails(applicationName: String, collaboratorEmail: String, adminEmail: String, adminsToEmail: Set[String]): Future[HttpResponse] = {
      if (adminsToEmail.nonEmpty) emailConnector.sendRemovedCollaboratorNotification(collaboratorEmail, applicationName, adminsToEmail)
      emailConnector.sendRemovedCollaboratorConfirmation(applicationName, Set(collaboratorEmail))
    }

    def audit(collaborator: Option[Collaborator]) =
      collaborator match {
        case Some(c) => auditService.audit(CollaboratorRemoved, AuditHelper.applicationId(applicationId) ++ CollaboratorRemoved.details(c))
        case None => Logger.warn(s"Failed to audit collaborator removal for: $collaborator")
      }

    for {
      app <- fetchApp(applicationId)
      updated <- deleteUser(app)
      _ = audit(app.collaborators.find(_.emailAddress == collaborator.toLowerCase))
      _ = recoverAll(sendEmails(app.name, collaborator.toLowerCase, admin, adminsToEmail))
    } yield updated.collaborators
  }

  private def hasAdmin(updated: Set[Collaborator]): Boolean = {
    updated.exists(_.role == Role.ADMINISTRATOR)
  }

  def fetchByClientId(clientId: String): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetchByClientId(clientId) map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def recordApplicationUsage(applicationId: UUID): Future[ApplicationResponse] =
    applicationRepository.recordApplicationUsage(applicationId)
      .map(application =>
        ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))

  def fetchByServerToken(serverToken: String): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetchByServerToken(serverToken) map {
      _.map(application =>
        ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def fetchAllForCollaborator(emailAddress: String): Future[Seq[ApplicationResponse]] = {
    applicationRepository.fetchAllForEmailAddress(emailAddress).map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def fetchAllForCollaboratorAndEnvironment(emailAddress: String, environment: String): Future[Seq[ApplicationResponse]] = {
    applicationRepository.fetchAllForEmailAddressAndEnvironment(emailAddress, environment).map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def fetchAll(): Future[Seq[ApplicationResponse]] = {
    applicationRepository.findAll().map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def fetchAllBySubscription(apiContext: String): Future[Seq[ApplicationResponse]] = {
    applicationRepository.fetchAllForContext(apiContext) map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def fetchAllBySubscription(apiIdentifier: APIIdentifier): Future[Seq[ApplicationResponse]] = {
    applicationRepository.fetchAllForApiIdentifier(apiIdentifier) map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def fetchAllWithNoSubscriptions(): Future[Seq[ApplicationResponse]] = {
    applicationRepository.fetchAllWithNoSubscriptions() map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def fetch(applicationId: UUID): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetch(applicationId) map {
      _.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application)))
    }
  }

  def searchApplications(applicationSearch: ApplicationSearch): Future[PaginatedApplicationResponse] = {
    applicationRepository.searchApplications(applicationSearch).map { data =>
      PaginatedApplicationResponse(
        page = applicationSearch.pageNumber,
        pageSize = applicationSearch.pageSize,
        total = data.totals.foldLeft(0)(_ + _.total),
        matching = data.matching.foldLeft(0)(_ + _.total),
        applications =
          data.applications.map(application => ApplicationResponse(data = application, trusted = trustedApplications.isTrusted(application))))
    }
  }

  def requestUplift(applicationId: UUID, applicationName: String,
                    requestedByEmailAddress: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {

    def uplift(existing: ApplicationData) = existing.copy(
      name = applicationName,
      normalisedName = applicationName.toLowerCase,
      state = existing.state.toPendingGatekeeperApproval(requestedByEmailAddress))

    for {
      app <- fetchApp(applicationId)
      upliftedApp = uplift(app)
      _ <- assertAppHasUniqueNameAndAudit(applicationName, app.access.accessType, Some(app))
      updatedApp <- applicationRepository.save(upliftedApp)
      _ <- insertStateHistory(
        app,
        PENDING_GATEKEEPER_APPROVAL, Some(TESTING),
        requestedByEmailAddress, COLLABORATOR,
        (a: ApplicationData) => applicationRepository.save(a)
      )
      _ = Logger.info(s"UPLIFT01: uplift request (pending) application:${app.name} appId:${app.id} appState:${app.state.name} " +
        s"appRequestedByEmailAddress:${app.state.requestedByEmailAddress}")
      _ = auditService.audit(ApplicationUpliftRequested,
        AuditHelper.applicationId(applicationId) ++ AuditHelper.calculateAppNameChange(app, updatedApp))
    } yield UpliftRequested
  }

  private def assertAppHasUniqueNameAndAudit(submittedAppName: String,
                                             accessType: AccessType,
                                             existingApp: Option[ApplicationData] = None)
                                            (implicit hc: HeaderCarrier) = {
    for {
      duplicate <- isDuplicateName(submittedAppName, existingApp.map(_.id))
      _ = if (duplicate) {
        accessType match {
          case PRIVILEGED => auditService.audit(CreatePrivilegedApplicationRequestDeniedDueToNonUniqueName,
            Map("applicationName" -> submittedAppName))
          case ROPC => auditService.audit(CreateRopcApplicationRequestDeniedDueToNonUniqueName,
            Map("applicationName" -> submittedAppName))
          case _ => auditService.audit(ApplicationUpliftRequestDeniedDueToNonUniqueName,
            AuditHelper.applicationId(existingApp.get.id) ++ Map("applicationName" -> submittedAppName))
        }
        throw ApplicationAlreadyExists(submittedAppName)
      }
    } yield ()
  }

  private def createApp(req: ApplicationRequest)(implicit hc: HeaderCarrier): Future[CreateApplicationResponse] = {
    val application = req.asInstanceOf[CreateApplicationRequest].normaliseCollaborators
    Logger.info(s"Creating application ${application.name}")

    val wso2Username = credentialGenerator.generate()
    val wso2Password = credentialGenerator.generate()
    val wso2ApplicationName = credentialGenerator.generate()

    def createInWso2(): Future[EnvironmentToken] = {
      apiGatewayStore.createApplication(wso2Username, wso2Password, wso2ApplicationName)
    }

    def saveApplication(environmentToken: EnvironmentToken, ids: Option[TotpIds]): Future[ApplicationData] = {

      def newPrivilegedAccess = {
        application.access.asInstanceOf[Privileged].copy(totpIds = ids)
      }

      val updatedApplication = ids match {
        case Some(_) if application.access.accessType == PRIVILEGED => application.copy(access = newPrivilegedAccess)
        case _ => application
      }

      val applicationData = ApplicationData.create(updatedApplication, wso2Username, wso2Password, wso2ApplicationName, environmentToken)

      applicationRepository.save(applicationData)
    }

    val f = for {
      _ <- application.access.accessType match {
        case PRIVILEGED => assertAppHasUniqueNameAndAudit(application.name, PRIVILEGED)
        case ROPC => assertAppHasUniqueNameAndAudit(application.name, ROPC)
        case _ => successful(Unit)
      }

      applicationTotps <- generateApplicationTotps(application.access.accessType)
      wso2EnvironmentToken <- createInWso2()
      appData <- saveApplication(wso2EnvironmentToken, extractTotpIds(applicationTotps))
      _ <- createStateHistory(appData)
      _ = auditAppCreated(appData)
    } yield applicationResponseCreator.createApplicationResponse(appData, extractTotpSecrets(applicationTotps))

    f andThen {
      case Failure(_) =>
        apiGatewayStore.deleteApplication(wso2Username, wso2Password, wso2ApplicationName)
          .map(_ => Logger.info(s"deleted application: [$wso2ApplicationName]"))
    }
  }

  private def generateApplicationTotps(accessType: AccessType)(implicit hc: HeaderCarrier): Future[Option[ApplicationTotps]] = {

    def generateTotps() = {
      val productionTotpFuture = totpConnector.generateTotp()
      val sandboxTotpFuture = totpConnector.generateTotp()
      for {
        productionTotp <- productionTotpFuture
        sandboxTotp <- sandboxTotpFuture
      } yield Some(ApplicationTotps(productionTotp, sandboxTotp))
    }

    accessType match {
      case PRIVILEGED => generateTotps()
      case _ => Future(None)
    }
  }

  private def extractTotpIds(applicationTotps: Option[ApplicationTotps]): Option[TotpIds] = {
    applicationTotps.map { t => TotpIds(t.production.id, t.sandbox.id) }
  }

  private def extractTotpSecrets(applicationTotps: Option[ApplicationTotps]): Option[TotpSecrets] = {
    applicationTotps.map { t => TotpSecrets(t.production.secret, t.sandbox.secret) }
  }

  def createStateHistory(appData: ApplicationData)(implicit hc: HeaderCarrier) = {
    val actor = appData.access.accessType match {
      case PRIVILEGED | ROPC => Actor("", GATEKEEPER)
      case _ => Actor(loggedInUser, COLLABORATOR)
    }
    insertStateHistory(appData, appData.state.name, None, actor.id, actor.actorType, (a: ApplicationData) => applicationRepository.delete(a.id))
  }

  private def auditAppCreated(app: ApplicationData)(implicit hc: HeaderCarrier) =
    auditService.audit(AppCreated, Map(
      "applicationId" -> app.id.toString,
      "newApplicationName" -> app.name,
      "newApplicationDescription" -> app.description.getOrElse("")
    ))

  private def updateApp(id: UUID)(application: ApplicationRequest)(implicit hc: HeaderCarrier): Future[ApplicationData] = {
    Logger.info(s"Updating application ${application.name}")

    def updatedAccess(existing: ApplicationData): Access =
      existing.access match {
        case Standard(_, _, _, o: Set[OverrideFlag]) => application.access.asInstanceOf[Standard].copy(overrides = o)
        case _ => application.access
      }

    def updatedApplication(existing: ApplicationData): ApplicationData =
      existing.copy(
        name = application.name,
        normalisedName = application.name.toLowerCase,
        description = application.description,
        access = updatedAccess(existing)
      )

    def checkAccessType(existing: ApplicationData): Unit =
      if (existing.access.accessType != application.access.accessType) {
        throw new ForbiddenException("Updating the access type of an application is not allowed")
      }

    for {
      existing <- fetchApp(id)
      _ = checkAccessType(existing)
      savedApp <- applicationRepository.save(updatedApplication(existing))
      _ = AuditHelper.calculateAppChanges(existing, savedApp).foreach(Function.tupled(auditService.audit))
    } yield savedApp
  }

  private def fetchApp(applicationId: UUID) = {
    val notFoundException = new NotFoundException(s"application not found for id: $applicationId")
    applicationRepository.fetch(applicationId).flatMap {
      case None => failed(notFoundException)
      case Some(app) => successful(app)
    }
  }

  def verifyUplift(verificationCode: String)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {

    def verifyProduction(app: ApplicationData) = {
      Logger.info(s"Application uplift for '${app.name}' has been verified already. No update was executed.")
      successful(UpliftVerified)
    }

    def findLatestUpliftRequester(appId: UUID): Future[String] = for {
      history <- stateHistoryRepository.fetchLatestByStateForApplication(appId, State.PENDING_GATEKEEPER_APPROVAL)
      state = history.getOrElse(throw new RuntimeException(s"Pending state not found for application: $appId"))
    } yield state.actor.id

    def audit(app: ApplicationData) =
      findLatestUpliftRequester(app.id) flatMap { email =>
        auditService.audit(ApplicationUpliftVerified, AuditHelper.applicationId(app.id), Map("upliftRequestedByEmail" -> email))
      }

    def verifyPending(app: ApplicationData) = for {
      _ <- applicationRepository.save(app.copy(state = app.state.toProduction))
      _ <- insertStateHistory(app, State.PRODUCTION, Some(PENDING_REQUESTER_VERIFICATION),
        app.state.requestedByEmailAddress.get, COLLABORATOR, (a: ApplicationData) => applicationRepository.save(a))
      _ = Logger.info(s"UPLIFT02: Application uplift for application:${app.name} appId:${app.id} has been verified successfully")
      _ = audit(app)
    } yield UpliftVerified

    for {
      app <- applicationRepository.fetchVerifiableUpliftBy(verificationCode)
        .map(_.getOrElse(throw InvalidUpliftVerificationCode(verificationCode)))

      result <- app.state.name match {
        case State.PRODUCTION => verifyProduction(app)
        case PENDING_REQUESTER_VERIFICATION => verifyPending(app)
        case _ => throw InvalidUpliftVerificationCode(verificationCode)
      }
    } yield result

  }

  private def isBlacklistedName(applicationName: String): Future[Boolean] = {
    def checkNameIsValid(blackListedName: String) = !applicationName.toLowerCase().contains(blackListedName.toLowerCase)

    val isValid = nameValidationConfig
      .nameBlackList
      .forall(name => checkNameIsValid(name))

    Future.successful(!isValid)
  }

  private def isDuplicateName(applicationName: String, thisApplicationId: Option[UUID])(implicit hc: HeaderCarrier): Future[Boolean] = {

    def isThisApplication(app: ApplicationData) = thisApplicationId.contains(app.id)

    def anyDuplicatesExcludingThis(apps: Seq[ApplicationData]): Boolean = {
      apps.exists(!isThisApplication(_))
    }

    if (nameValidationConfig.validateForDuplicateAppNames) {
      applicationRepository
        .fetchApplicationsByName(applicationName)
        .map(anyDuplicatesExcludingThis)
    } else {
      Future.successful(false)
    }
  }

  def validateApplicationName(applicationName: String, selfApplicationId: Option[UUID])
                             (implicit hc: HeaderCarrier): Future[ApplicationNameValidationResult] = {
    for {
      isBlacklisted <- isBlacklistedName(applicationName)
      isDuplicate <- isDuplicateName(applicationName, selfApplicationId)
    } yield (isBlacklisted, isDuplicate) match {
      case (false, false) => Valid
      case (blacklist, duplicate) => Invalid(blacklist, duplicate)
    }
  }

  private def insertStateHistory(snapshotApp: ApplicationData, newState: State, oldState: Option[State],
                                 requestedBy: String, actorType: ActorType.ActorType, rollback: ApplicationData => Any) = {
    val stateHistory = StateHistory(snapshotApp.id, newState, Actor(requestedBy, actorType), oldState)
    stateHistoryRepository.insert(stateHistory) andThen {
      case Failure(_) =>
        rollback(snapshotApp)
    }
  }

  val recoverAll: Future[_] => Future[_] = {
    _ recover {
      case e: Throwable => Logger.error(e.getMessage); (): Unit
    }
  }

  private def loggedInUser(implicit hc: HeaderCarrier) = hc.headers find (_._1 == LOGGED_IN_USER_EMAIL_HEADER) map (_._2) getOrElse ""

}

@Singleton
class ApplicationLockKeeper @Inject()(reactiveMongoComponent: ReactiveMongoComponent) extends LockKeeper {
  override def repo: LockRepository = {
    LockMongoRepository(reactiveMongoComponent.mongoConnector.db)
  }

  override def lockId: String = "create-third-party-application"

  override val forceLockReleaseAfter = standardMinutes(1)
}
