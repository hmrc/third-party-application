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

package uk.gov.hmrc.thirdpartyapplication.services

import akka.actor.ActorSystem
import org.apache.commons.net.util.SubnetUtils
import org.joda.time.Duration.standardMinutes
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.ForbiddenException
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.lock.LockKeeper
import uk.gov.hmrc.lock.LockMongoRepository
import uk.gov.hmrc.lock.LockRepository
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.AddCollaboratorRequest
import uk.gov.hmrc.thirdpartyapplication.controllers.AddCollaboratorResponse
import uk.gov.hmrc.thirdpartyapplication.controllers.DeleteApplicationRequest
import uk.gov.hmrc.thirdpartyapplication.controllers.FixCollaboratorRequest
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.repository.StateHistoryRepository
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator
import uk.gov.hmrc.thirdpartyapplication.util.http.HeaderCarrierUtils._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Future.{apply => _, _}
import scala.concurrent.duration.Duration
import scala.util.Failure
import scala.util.Try
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService

import java.time.{Clock, LocalDateTime}

@Singleton
class ApplicationService @Inject() (
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    subscriptionRepository: SubscriptionRepository,
    auditService: AuditService,
    apiPlatformEventService: ApiPlatformEventService,
    emailConnector: EmailConnector,
    totpConnector: TotpConnector,
    system: ActorSystem,
    lockKeeper: ApplicationLockKeeper,
    apiGatewayStore: ApiGatewayStore,
    applicationResponseCreator: ApplicationResponseCreator,
    credentialGenerator: CredentialGenerator,
    apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
    thirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector,
    tokenService: TokenService,
    submissionsService: SubmissionsService,
    upliftNamingService: UpliftNamingService,
    clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  def create(application: CreateApplicationRequest)(implicit hc: HeaderCarrier): Future[CreateApplicationResponse] = {

    lockKeeper.tryLock {
      createApp(application)
    } flatMap {
      case Some(x) =>
        logger.info(s"Application ${application.name} has been created successfully")
        Future(x)
      case None    =>
        logger.warn(s"Application creation is locked. Retry scheduled for ${application.name}")
        akka.pattern.after(Duration(3, TimeUnit.SECONDS), using = system.scheduler) {
          create(application)
        }
    }
  }

  def update(applicationId: ApplicationId, application: UpdateApplicationRequest)(implicit hc: HeaderCarrier): Future[ApplicationResponse] = {
    updateApp(applicationId)(application) map (app => ApplicationResponse(data = app))
  }

  def updateCheck(applicationId: ApplicationId, checkInformation: CheckInformation): Future[ApplicationResponse] = {
    for {
      existing <- fetchApp(applicationId)
      savedApp <- applicationRepository.save(existing.copy(checkInformation = Some(checkInformation)))
    } yield ApplicationResponse(data = savedApp)
  }

  def addCollaborator(applicationId: ApplicationId, request: AddCollaboratorRequest)(implicit hc: HeaderCarrier) = {

    def validateCollaborator(app: ApplicationData, email: String, role: Role, userId: UserId): Collaborator = {
      val normalised = email.toLowerCase
      if (app.collaborators.exists(_.emailAddress == normalised)) throw new UserAlreadyExists

      Collaborator(normalised, role, userId)
    }

    def addUser(app: ApplicationData, collaborator: Collaborator): Future[Set[Collaborator]] = {
      val updated = app.collaborators + collaborator
      applicationRepository.save(app.copy(collaborators = updated)) map (_.collaborators)
    }

    def sendNotificationEmails(
        applicationName: String,
        collaborator: Collaborator,
        registeredUser: Boolean,
        adminsToEmail: Set[String]
      )(implicit hc: HeaderCarrier
      ): Future[HasSucceeded] = {
      def roleForEmail(role: Role) = {
        role match {
          case ADMINISTRATOR => "admin"
          case DEVELOPER     => "developer"
          case _             => throw new RuntimeException(s"Unexpected role $role")
        }
      }

      val role: String = roleForEmail(collaborator.role)

      if (adminsToEmail.nonEmpty) {
        emailConnector.sendAddedCollaboratorNotification(collaborator.emailAddress, role, applicationName, adminsToEmail)
      }

      emailConnector.sendAddedCollaboratorConfirmation(role, applicationName, Set(collaborator.emailAddress))
    }

    for {
      app         <- fetchApp(applicationId)
      collaborator = validateCollaborator(app, request.collaborator.emailAddress, request.collaborator.role, request.collaborator.userId)
      _           <- addUser(app, collaborator)
      _            = auditService.audit(CollaboratorAdded, AuditHelper.applicationId(app.id) ++ CollaboratorAdded.details(collaborator))
      _            = apiPlatformEventService.sendTeamMemberAddedEvent(app, collaborator.emailAddress, collaborator.role.toString)
      _            = sendNotificationEmails(app.name, collaborator, request.isRegistered, request.adminsToEmail)
    } yield AddCollaboratorResponse(request.isRegistered)
  }

  def addTermsOfUseAcceptance(applicationId: ApplicationId, acceptance: TermsOfUseAcceptance): Future[ApplicationData] = {
    for {
      updatedApp <- applicationRepository.addApplicationTermsOfUseAcceptance(applicationId, acceptance)
    } yield updatedApp
  }

  def confirmSetupComplete(applicationId: ApplicationId, requesterEmailAddress: String): Future[ApplicationData] = {
    for {
      app            <- fetchApp(applicationId)
      oldState        = app.state
      newState        = app.state.toProduction(clock)
      appWithNewState = app.copy(state = newState)
      updatedApp     <- applicationRepository.save(appWithNewState)
      stateHistory    = StateHistory(applicationId, newState.name, OldActor(requesterEmailAddress, COLLABORATOR), Some(oldState.name), None, app.state.updatedOn)
      _              <- stateHistoryRepository.insert(stateHistory)
    } yield updatedApp
  }

  def updateRateLimitTier(applicationId: ApplicationId, rateLimitTier: RateLimitTier)(implicit hc: HeaderCarrier): Future[ApplicationData] = {
    logger.info(s"Trying to update the rate limit tier to $rateLimitTier for application ${applicationId.value}")

    for {
      app                <- fetchApp(applicationId)
      _                  <- apiGatewayStore.updateApplication(app, rateLimitTier)
      updatedApplication <- applicationRepository.updateApplicationRateLimit(applicationId, rateLimitTier)
    } yield updatedApplication
  }

  def updateIpAllowlist(applicationId: ApplicationId, newIpAllowlist: IpAllowlist): Future[ApplicationData] = {
    for {
      _          <- fromTry(Try(newIpAllowlist.allowlist.foreach(new SubnetUtils(_)))) recover {
                      case e: IllegalArgumentException => throw InvalidIpAllowlistException(e.getMessage)
                    }
      updatedApp <- applicationRepository.updateApplicationIpAllowlist(applicationId, newIpAllowlist)
    } yield updatedApp
  }

  def updateGrantLength(applicationId: ApplicationId, newGrantLength: Int): Future[ApplicationData] = {
    logger.info(s"Trying to update the Grant Length  $newGrantLength for application ${applicationId.value}")

    for {
      updatedApp <- applicationRepository.updateApplicationGrantLength(applicationId, newGrantLength)
    } yield updatedApp
  }

  def deleteApplication(
      applicationId: ApplicationId,
      request: Option[DeleteApplicationRequest],
      auditFunction: ApplicationData => Future[AuditResult]
    )(implicit hc: HeaderCarrier
    ): Future[ApplicationStateChange] = {
    logger.info(s"Deleting application ${applicationId.value}")

    def deleteSubscriptions(app: ApplicationData): Future[HasSucceeded] = {
      def deleteSubscription(subscription: ApiIdentifier) = {
        for {
          _ <- subscriptionRepository.remove(app.id, subscription)
        } yield HasSucceeded
      }

      for {
        subscriptions <- subscriptionRepository.getSubscriptions(applicationId)
        _             <- traverse(subscriptions)(deleteSubscription)
        _             <- apiSubscriptionFieldsConnector.deleteSubscriptions(app.tokens.production.clientId)
      } yield HasSucceeded
    }

    def sendEmailsIfRequestedByEmailAddressPresent(app: ApplicationData): Future[Any] = {
      request match {
        case Some(r) => {
          val requesterEmail = r.requestedByEmailAddress
          val recipients     = app.admins.map(_.emailAddress)
          emailConnector.sendApplicationDeletedNotification(app.name, app.id, requesterEmail, recipients)
        }
        case None    => successful(())
      }
    }

    (for {
      app <- fetchApp(applicationId)
      _   <- deleteSubscriptions(app)
      _   <- thirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(app.tokens.production.clientId)
      _   <- apiGatewayStore.deleteApplication(app.wso2ApplicationName)
      _   <- applicationRepository.delete(applicationId)
      _   <- submissionsService.deleteAllAnswersForApplication(app.id)
      _   <- stateHistoryRepository.deleteByApplicationId(applicationId)
      _    = auditFunction(app)
      _    = recoverAll(sendEmailsIfRequestedByEmailAddressPresent(app))
    } yield Deleted).recover {
      case _: NotFoundException => Deleted
    }
  }

  def deleteCollaborator(
      applicationId: ApplicationId,
      collaborator: String,
      adminsToEmail: Set[String],
      notifyCollaborator: Boolean
    )(implicit hc: HeaderCarrier
    ): Future[Set[Collaborator]] = {
    def deleteUser(app: ApplicationData): Future[ApplicationData] = {
      val updatedCollaborators = app.collaborators.filterNot(_.emailAddress equalsIgnoreCase collaborator)
      if (!hasAdmin(updatedCollaborators)) failed(new ApplicationNeedsAdmin)
      else applicationRepository.save(app.copy(collaborators = updatedCollaborators))
    }

    def sendEmails(applicationName: String, collaboratorEmail: String, adminsToEmail: Set[String]): Future[Unit] = {
      if (adminsToEmail.nonEmpty) emailConnector.sendRemovedCollaboratorNotification(collaboratorEmail, applicationName, adminsToEmail)
      if (notifyCollaborator) emailConnector.sendRemovedCollaboratorConfirmation(applicationName, Set(collaboratorEmail)).map(_ => ()) else successful(())
    }

    def audit(collaborator: Option[Collaborator]) = collaborator match {
      case Some(c) => auditService.audit(CollaboratorRemoved, AuditHelper.applicationId(applicationId) ++ CollaboratorRemoved.details(c))
      case None    => logger.warn(s"Failed to audit collaborator removal for: $collaborator")
    }

    def findCollaborator(app: ApplicationData): Option[Collaborator] = app.collaborators.find(_.emailAddress == collaborator.toLowerCase)

    def sendEvent(app: ApplicationData, maybeColab: Option[Collaborator]) = maybeColab match {
      case Some(collaborator) => apiPlatformEventService.sendTeamMemberRemovedEvent(app, collaborator.emailAddress, collaborator.role.toString)
      case None               => logger.warn(s"Failed to send TeamMemberRemovedEvent for appId: ${app.id}")
    }

    for {
      app     <- fetchApp(applicationId)
      updated <- deleteUser(app)
      _        = audit(findCollaborator(app))
      _        = sendEvent(app, findCollaborator(app))
      _        = recoverAll(sendEmails(app.name, collaborator.toLowerCase, adminsToEmail))
    } yield updated.collaborators
  }

  def fixCollaborator(applicationId: ApplicationId, fixCollaboratorRequest: FixCollaboratorRequest): Future[Option[ApplicationData]] = {
    applicationRepository.updateCollaboratorId(applicationId, fixCollaboratorRequest.emailAddress, fixCollaboratorRequest.userId)
  }

  private def hasAdmin(updated: Set[Collaborator]): Boolean = {
    updated.exists(_.role == Role.ADMINISTRATOR)
  }

  def fetchByClientId(clientId: ClientId): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetchByClientId(clientId) map {
      _.map(application => ApplicationResponse(data = application))
    }
  }

  def recordApplicationUsage(applicationId: ApplicationId): Future[ExtendedApplicationResponse] = {
    for {
      app           <- applicationRepository.recordApplicationUsage(applicationId)
      subscriptions <- subscriptionRepository.getSubscriptions(app.id)
    } yield ExtendedApplicationResponse(app, subscriptions)
  }

  def recordServerTokenUsage(applicationId: ApplicationId): Future[ExtendedApplicationResponse] = {
    for {
      app           <- applicationRepository.recordServerTokenUsage(applicationId)
      subscriptions <- subscriptionRepository.getSubscriptions(app.id)
    } yield ExtendedApplicationResponse(app, subscriptions)
  }

  def fetchByServerToken(serverToken: String): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetchByServerToken(serverToken) map {
      _.map(application =>
        ApplicationResponse(data = application)
      )
    }
  }

  // TODO - introduce me
  // private def asResponse(apps: List[ApplicationData]): List[ApplicationResponse] = {
  //   apps.map(ApplicationResponse(data = _))
  // }

  private def asExtendedResponses(apps: List[ApplicationData]): Future[List[ExtendedApplicationResponse]] = {
    def asExtendedResponse(app: ApplicationData): Future[ExtendedApplicationResponse] = {
      subscriptionRepository.getSubscriptions(app.id).map(subscriptions => ExtendedApplicationResponse(app, subscriptions))
    }

    Future.sequence(apps.map(asExtendedResponse))
  }

  def fetchAllForCollaborator(userId: UserId): Future[List[ExtendedApplicationResponse]] = {
    applicationRepository.fetchAllForUserId(userId).flatMap(asExtendedResponses)
  }

  def fetchAllForUserIdAndEnvironment(userId: UserId, environment: String): Future[List[ExtendedApplicationResponse]] = {
    applicationRepository.fetchAllForUserIdAndEnvironment(userId, environment)
      .flatMap(asExtendedResponses)
  }

  def fetchAll(): Future[List[ApplicationResponse]] = {
    applicationRepository.findAll().map {
      _.map(application => ApplicationResponse(data = application))
    }
  }

  def fetchAllBySubscription(apiContext: ApiContext): Future[List[ApplicationResponse]] = {
    applicationRepository.fetchAllForContext(apiContext) map {
      _.map(application => ApplicationResponse(data = application))
    }
  }

  def fetchAllBySubscription(apiIdentifier: ApiIdentifier): Future[List[ApplicationResponse]] = {
    applicationRepository.fetchAllForApiIdentifier(apiIdentifier) map {
      _.map(application => ApplicationResponse(data = application))
    }
  }

  def fetchAllWithNoSubscriptions(): Future[List[ApplicationResponse]] = {
    applicationRepository.fetchAllWithNoSubscriptions() map {
      _.map(application => ApplicationResponse(data = application))
    }
  }

  import cats.data.OptionT
  import cats.implicits._

  def fetch(applicationId: ApplicationId): OptionT[Future, ApplicationResponse] =
    OptionT(applicationRepository.fetch(applicationId))
      .map(application => ApplicationResponse(data = application))

  def searchApplications(applicationSearch: ApplicationSearch): Future[PaginatedApplicationResponse] = {
    applicationRepository.searchApplications(applicationSearch).map { data =>
      PaginatedApplicationResponse(
        page = applicationSearch.pageNumber,
        pageSize = applicationSearch.pageSize,
        total = data.totals.foldLeft(0)(_ + _.total),
        matching = data.matching.foldLeft(0)(_ + _.total),
        applications = data.applications.map(application => ApplicationResponse(data = application))
      )
    }
  }

  private def createApp(req: CreateApplicationRequest)(implicit hc: HeaderCarrier): Future[CreateApplicationResponse] = {
    val createApplicationRequest: CreateApplicationRequest = req match {
      case v1: CreateApplicationRequestV1 => v1.normaliseCollaborators
      case v2: CreateApplicationRequestV2 => v2.normaliseCollaborators
    }

    logger.info(s"Creating application ${createApplicationRequest.name}")

    val wso2ApplicationName = credentialGenerator.generate()

    def createInApiGateway(appData: ApplicationData): Future[HasSucceeded] = {
      if (appData.isInPreProductionOrProduction) {
        apiGatewayStore.createApplication(appData.wso2ApplicationName, appData.tokens.production.accessToken)
      } else {
        successful(HasSucceeded)
      }
    }

    def applyTotpForPrivAppsOnly(totp: Option[Totp], request: CreateApplicationRequest): CreateApplicationRequest = {
      request match {
        case v1 @ CreateApplicationRequestV1(_, priv: Privileged, _, _, _, _) => v1.copy(access = priv.copy(totpIds = extractTotpId(totp)))
        case _                                                                => request
      }
    }

    val f = for {
      _              <- createApplicationRequest.accessType match {
                          case PRIVILEGED => upliftNamingService.assertAppHasUniqueNameAndAudit(createApplicationRequest.name, PRIVILEGED)
                          case ROPC       => upliftNamingService.assertAppHasUniqueNameAndAudit(createApplicationRequest.name, ROPC)
                          case _          => successful(Unit)
                        }
      totp           <- generateApplicationTotp(createApplicationRequest.accessType)
      modifiedRequest = applyTotpForPrivAppsOnly(totp, req)
      appData         = ApplicationData.create(modifiedRequest, wso2ApplicationName, tokenService.createEnvironmentToken(), LocalDateTime.now(clock))
      _              <- createInApiGateway(appData)
      _              <- applicationRepository.save(appData)
      _              <- createStateHistory(appData)
      _               = auditAppCreated(appData)
    } yield applicationResponseCreator.createApplicationResponse(appData, extractTotpSecret(totp))

    f andThen {
      case Failure(_) =>
        apiGatewayStore.deleteApplication(wso2ApplicationName)
          .map(_ => logger.info(s"deleted application: [$wso2ApplicationName]"))
    }
  }

  private def generateApplicationTotp(accessType: AccessType)(implicit hc: HeaderCarrier): Future[Option[Totp]] = {
    accessType match {
      case PRIVILEGED => totpConnector.generateTotp().map(Some(_))
      case _          => Future(None)
    }
  }

  private def extractTotpId(totp: Option[Totp]): Option[TotpId] = {
    totp.map { t => TotpId(t.id) }
  }

  private def extractTotpSecret(totp: Option[Totp]): Option[TotpSecret] = {
    totp.map { t => TotpSecret(t.secret) }
  }

  def createStateHistory(appData: ApplicationData)(implicit hc: HeaderCarrier) = {
    val actor = appData.access.accessType match {
      case PRIVILEGED | ROPC => OldActor("", GATEKEEPER)
      case _                 => OldActor(loggedInUser, COLLABORATOR)
    }
    insertStateHistory(appData, appData.state.name, None, actor.id, actor.actorType, (a: ApplicationData) => applicationRepository.delete(a.id))
  }

  private def auditAppCreated(app: ApplicationData)(implicit hc: HeaderCarrier) =
    auditService.audit(
      AppCreated,
      Map(
        "applicationId"             -> app.id.value.toString,
        "newApplicationName"        -> app.name,
        "newApplicationDescription" -> app.description.getOrElse("")
      )
    )

  private def updateApp(applicationId: ApplicationId)(applicationRequest: UpdateApplicationRequest)(implicit hc: HeaderCarrier): Future[ApplicationData] = {
    logger.info(s"Updating application ${applicationRequest.name}")

    def updatedAccess(existing: ApplicationData): Access =
      (applicationRequest.access, existing.access) match {
        case (newAccess: Standard, oldAccess: Standard) => newAccess.copy(overrides = oldAccess.overrides)
        case _                                          => applicationRequest.access
      }

    def updatedApplication(existing: ApplicationData): ApplicationData =
      existing.copy(
        name = applicationRequest.name,
        normalisedName = applicationRequest.name.toLowerCase,
        description = applicationRequest.description,
        access = updatedAccess(existing)
      )

    def checkAccessType(existing: ApplicationData): Unit =
      if (existing.access.accessType != applicationRequest.access.accessType) {
        throw new ForbiddenException("Updating the access type of an application is not allowed")
      }

    for {
      existing <- fetchApp(applicationId)
      _         = checkAccessType(existing)
      savedApp <- applicationRepository.save(updatedApplication(existing))
      _         = sendEventIfRedirectUrisChanged(existing, savedApp)
      _         = AuditHelper.calculateAppChanges(existing, savedApp).foreach(Function.tupled(auditService.audit))
    } yield savedApp
  }

  private def sendEventIfRedirectUrisChanged(previousAppData: ApplicationData, updatedAppData: ApplicationData)(implicit hc: HeaderCarrier): Unit = {
    (previousAppData.access, updatedAppData.access) match {
      case (previous: Standard, updated: Standard) =>
        if (previous.redirectUris != updated.redirectUris) {
          apiPlatformEventService.sendRedirectUrisUpdatedEvent(updatedAppData, previous.redirectUris.mkString(","), updated.redirectUris.mkString(","))
        }
      case _                                       => ()
    }
  }

  private def fetchApp(applicationId: ApplicationId) = {
    val notFoundException = new NotFoundException(s"application not found for id: ${applicationId.value}")
    applicationRepository.fetch(applicationId).flatMap {
      case None      => failed(notFoundException)
      case Some(app) => successful(app)
    }
  }

  private def insertStateHistory(
      snapshotApp: ApplicationData,
      newState: State,
      oldState: Option[State],
      requestedBy: String,
      actorType: ActorType.ActorType,
      rollback: ApplicationData => Any
    ) = {
    val stateHistory = StateHistory(snapshotApp.id, newState, OldActor(requestedBy, actorType), oldState, changedAt = LocalDateTime.now(clock))
    stateHistoryRepository.insert(stateHistory) andThen {
      case Failure(_) =>
        rollback(snapshotApp)
    }
  }

  val recoverAll: Future[_] => Future[_] = {
    _ recover {
      case e: Throwable => logger.error(e.getMessage); (): Unit
    }
  }

  private def loggedInUser(implicit hc: HeaderCarrier) =
    hc.valueOf(LOGGED_IN_USER_EMAIL_HEADER)
      .getOrElse("")
}

@Singleton
class ApplicationLockKeeper @Inject() (reactiveMongoComponent: ReactiveMongoComponent) extends LockKeeper {

  override def repo: LockRepository = {
    LockMongoRepository(reactiveMongoComponent.mongoConnector.db)
  }

  override def lockId: String = "create-third-party-application"

  override val forceLockReleaseAfter = standardMinutes(1)
}
