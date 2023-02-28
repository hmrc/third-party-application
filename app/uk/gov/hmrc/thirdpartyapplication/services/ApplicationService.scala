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

package uk.gov.hmrc.thirdpartyapplication.services

import java.time.{Clock, LocalDateTime}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.{apply => _, _}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

import akka.actor.ActorSystem
import org.apache.commons.net.util.SubnetUtils

import uk.gov.hmrc.http.{BadRequestException, ForbiddenException, HeaderCarrier, NotFoundException}
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientId, Collaborator}
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.developers.domain.models.UserId
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.{AddCollaboratorRequest, AddCollaboratorResponse, DeleteApplicationRequest, FixCollaboratorRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler
import uk.gov.hmrc.thirdpartyapplication.util.http.HeaderCarrierUtils._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.util.{ActorHelper, CredentialGenerator, HeaderCarrierHelper}

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
    lockService: ApplicationLockService,
    apiGatewayStore: ApiGatewayStore,
    applicationResponseCreator: ApplicationResponseCreator,
    credentialGenerator: CredentialGenerator,
    apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
    thirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector,
    tokenService: TokenService,
    submissionsService: SubmissionsService,
    upliftNamingService: UpliftNamingService,
    applicationCommandDispatcher: ApplicationCommandDispatcher,
    clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with ActorHelper {

  def create(application: CreateApplicationRequest)(implicit hc: HeaderCarrier): Future[CreateApplicationResponse] = {

    lockService.withLock {
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

  @deprecated("please use AddCollaboratorRequest command to application Update controller")
  def addCollaborator(applicationId: ApplicationId, request: AddCollaboratorRequest)(implicit hc: HeaderCarrier) = {

    def validateCollaborator(app: ApplicationData, collaborator: Collaborator): Collaborator = {
      val normalisedCollaborator = request.collaborator.normalise

      if (app.collaborators.exists(_.emailAddress == normalisedCollaborator.emailAddress)) throw new UserAlreadyExists

      normalisedCollaborator
    }

    def addUser(app: ApplicationData, collaborator: Collaborator): Future[Set[Collaborator]] = {
      val updated = app.collaborators + collaborator
      applicationRepository.save(app.copy(collaborators = updated)) map (_.collaborators)
    }

    def sendNotificationEmails(
        applicationName: String,
        collaborator: Collaborator,
        adminsToEmail: Set[LaxEmailAddress]
      )(implicit hc: HeaderCarrier
      ): Future[HasSucceeded] = {

      if (adminsToEmail.nonEmpty) {
        emailConnector.sendCollaboratorAddedNotification(collaborator, applicationName, adminsToEmail)
      }

      emailConnector.sendCollaboratorAddedConfirmation(collaborator, applicationName, Set(collaborator.emailAddress))
    }

    for {
      app         <- fetchApp(applicationId)
      collaborator = validateCollaborator(app, request.collaborator)
      _           <- addUser(app, collaborator)
      _            = auditService.audit(CollaboratorAddedAudit, AuditHelper.applicationId(app.id) ++ CollaboratorAddedAudit.details(collaborator))
      _            = apiPlatformEventService.sendTeamMemberAddedEvent(app, collaborator.emailAddress, collaborator.describeRole)
      _            = sendNotificationEmails(app.name, collaborator, request.adminsToEmail)
    } yield AddCollaboratorResponse(request.isRegistered)
  }

  def addTermsOfUseAcceptance(applicationId: ApplicationId, acceptance: TermsOfUseAcceptance): Future[ApplicationData] = {
    for {
      updatedApp <- applicationRepository.addApplicationTermsOfUseAcceptance(applicationId, acceptance)
    } yield updatedApp
  }

  def confirmSetupComplete(applicationId: ApplicationId, requesterEmailAddress: LaxEmailAddress): Future[ApplicationData] = {
    for {
      app            <- fetchApp(applicationId)
      oldState        = app.state
      newState        = app.state.toProduction(clock)
      appWithNewState = app.copy(state = newState)
      updatedApp     <- applicationRepository.save(appWithNewState)
      stateHistory    = StateHistory(applicationId, newState.name, Actors.AppCollaborator(requesterEmailAddress), Some(oldState.name), None, app.state.updatedOn)
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
      _   <- applicationRepository.hardDelete(applicationId)
      _   <- submissionsService.deleteAllAnswersForApplication(app.id)
      _   <- stateHistoryRepository.deleteByApplicationId(applicationId)
      _    = auditFunction(app)
      _    = recoverAll(sendEmailsIfRequestedByEmailAddressPresent(app))
    } yield Deleted).recover {
      case _: NotFoundException => Deleted
    }
  }

  @deprecated("please use RemoveCollaboratorRequest command to application Update controller")
  def deleteCollaborator(
      applicationId: ApplicationId,
      collaborator: LaxEmailAddress,
      adminsToEmail: Set[LaxEmailAddress],
      notifyCollaborator: Boolean
    )(implicit hc: HeaderCarrier
    ): Future[Set[Collaborator]] = {
    def deleteUser(app: ApplicationData): Future[ApplicationData] = {
      val updatedCollaborators = app.collaborators.filterNot(_.emailAddress equalsIgnoreCase (collaborator))
      if (!hasAdmin(updatedCollaborators)) failed(new ApplicationNeedsAdmin)
      else applicationRepository.save(app.copy(collaborators = updatedCollaborators))
    }

    def sendEmails(applicationName: String, collaboratorEmail: LaxEmailAddress, adminsToEmail: Set[LaxEmailAddress]): Future[Unit] = {
      if (adminsToEmail.nonEmpty) emailConnector.sendRemovedCollaboratorNotification(collaboratorEmail, applicationName, adminsToEmail)
      if (notifyCollaborator) emailConnector.sendRemovedCollaboratorConfirmation(applicationName, Set(collaboratorEmail)).map(_ => ()) else successful(())
    }

    def audit(collaborator: Option[Collaborator]) = collaborator match {
      case Some(c) => auditService.audit(CollaboratorRemovedAudit, AuditHelper.applicationId(applicationId) ++ CollaboratorRemovedAudit.details(c))
      case None    => logger.warn(s"Failed to audit collaborator removal for: $collaborator")
    }

    def findCollaborator(app: ApplicationData): Option[Collaborator] = app.collaborators.find(_.emailAddress.normalise == collaborator.normalise)

    def sendEvent(app: ApplicationData, maybeColab: Option[Collaborator]) = maybeColab match {
      case Some(collaborator) => apiPlatformEventService.sendTeamMemberRemovedEvent(app, collaborator.emailAddress, collaborator.describeRole)
      case None               => logger.warn(s"Failed to send TeamMemberRemovedEvent for appId: ${app.id}")
    }

    for {
      app     <- fetchApp(applicationId)
      updated <- deleteUser(app)
      _        = audit(findCollaborator(app))
      _        = sendEvent(app, findCollaborator(app))
      _        = recoverAll(sendEmails(app.name, collaborator.normalise, adminsToEmail))
    } yield updated.collaborators
  }

  def fixCollaborator(applicationId: ApplicationId, fixCollaboratorRequest: FixCollaboratorRequest): Future[Option[ApplicationData]] = {
    applicationRepository.updateCollaboratorId(applicationId, fixCollaboratorRequest.emailAddress, fixCollaboratorRequest.userId)
  }

  private def hasAdmin(updated: Set[Collaborator]): Boolean = {
    updated.exists(_.isAdministrator)
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

  def fetchAllForCollaborator(userId: UserId, includeDeleted: Boolean): Future[List[ExtendedApplicationResponse]] = {
    applicationRepository.fetchAllForUserId(userId, includeDeleted).flatMap(x => asExtendedResponses(x.toList))
  }

  def fetchAllForUserIdAndEnvironment(userId: UserId, environment: String): Future[List[ExtendedApplicationResponse]] = {
    applicationRepository.fetchAllForUserIdAndEnvironment(userId, environment)
      .flatMap(x => asExtendedResponses(x.toList))
  }

  def fetchAll(): Future[List[ApplicationResponse]] = {
    applicationRepository.fetchAll().map {
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
      case PRIVILEGED | ROPC => Actors.Unknown
      case _                 => loggedInActor
    }
    insertStateHistory(appData, appData.state.name, None, actor, (a: ApplicationData) => applicationRepository.hardDelete(a.id))
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
      existing   <- fetchApp(applicationId)
      _           = checkAccessType(existing)
      savedApp   <- applicationRepository.save(updatedApplication(existing))
      _           = AuditHelper.calculateAppChanges(existing, savedApp).foreach(Function.tupled(auditService.audit))
      updatedApp <- sendEventAndAuditIfRedirectUrisChanged(existing, savedApp)
    } yield updatedApp
  }

  private def sendEventAndAuditIfRedirectUrisChanged(previousAppData: ApplicationData, updatedAppData: ApplicationData)(implicit hc: HeaderCarrier): Future[ApplicationData] = {
    (previousAppData.access, updatedAppData.access) match {
      case (previous: Standard, updated: Standard) =>
        if (previous.redirectUris != updated.redirectUris) {
          handleUpdateApplication(
            previousAppData.id,
            updatedAppData.collaborators,
            oldRedirectUris = previous.redirectUris,
            newRedirectUris = updated.redirectUris
          )
        } else Future.successful(updatedAppData)
      case _                                       => Future.successful(updatedAppData)
    }
  }

  private def handleUpdateApplication(
      applicationId: ApplicationId,
      collaborators: Set[Collaborator],
      oldRedirectUris: List[String],
      newRedirectUris: List[String]
    )(implicit hc: HeaderCarrier
    ): Future[ApplicationData] = {

    def fail(errorMessages: CommandHandler.CommandFailures) = {
      logger.warn(s"Command Process failed for $applicationId because ${errorMessages.toList.mkString("[", ",", "]")}")
      throw new BadRequestException("Failed to process UpdateRedirectUris command")
    }

    def success(cmdSuccess: CommandHandler.CommandSuccess) = {
      cmdSuccess._1
    }

    val updateRedirectUris = UpdateRedirectUris(
      actor = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), collaborators).getOrElse(Actors.Unknown),
      oldRedirectUris,
      newRedirectUris,
      timestamp = LocalDateTime.now(clock)
    )

    applicationCommandDispatcher.dispatch(applicationId, updateRedirectUris)
      .fold(fail, success)
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
      actor: Actor,
      rollback: ApplicationData => Any
    ) = {
    val stateHistory = StateHistory(snapshotApp.id, newState, actor, oldState, changedAt = LocalDateTime.now(clock))
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

  private def loggedInActor(implicit hc: HeaderCarrier): Actor =
    hc.valueOf(LOGGED_IN_USER_EMAIL_HEADER).fold[Actor](Actors.Unknown)(text => Actors.AppCollaborator(LaxEmailAddress(text)))
}

@Singleton
class ApplicationLockService @Inject() (repository: LockRepository)
    extends LockService {

  override val lockRepository: LockRepository = repository
  override val lockId: String                 = "create-third-party-application"
  override val ttl: Duration                  = 1.minutes
}
