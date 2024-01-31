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

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.{apply => _, _}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import akka.actor.ActorSystem
import cats.data.OptionT
import com.kenshoo.play.metrics.Metrics

import uk.gov.hmrc.http.{ForbiddenException, HeaderCarrier, NotFoundException}
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress, _}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{CheckInformation, State, StateHistory}
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models._
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.TermsOfUseAcceptance
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.{DeleteApplicationRequest, FixCollaboratorRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, Deleted}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, StateHistoryRepository, SubscriptionRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.http.HeaderCarrierUtils._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.util.{ActorHelper, CredentialGenerator, MetricsTimer}

@Singleton
class ApplicationService @Inject() (
    val metrics: Metrics,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    subscriptionRepository: SubscriptionRepository,
    notificationRepository: NotificationRepository,
    responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    auditService: AuditService,
    apiPlatformEventService: ApiPlatformEventService,
    emailConnector: EmailConnector,
    totpConnector: TotpConnector,
    system: ActorSystem,
    lockService: ApplicationLockService,
    apiGatewayStore: ApiGatewayStore,
    credentialGenerator: CredentialGenerator,
    apiSubscriptionFieldsConnector: ApiSubscriptionFieldsConnector,
    thirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector,
    tokenService: TokenService,
    submissionsService: SubmissionsService,
    upliftNamingService: UpliftNamingService,
    applicationCommandDispatcher: ApplicationCommandDispatcher,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends MetricsTimer with ApplicationLogger with ActorHelper with ClockNow {

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

  def update(applicationId: ApplicationId, application: UpdateApplicationRequest)(implicit hc: HeaderCarrier): Future[Application] = {
    updateApp(applicationId)(application) map (app => Application(data = app))
  }

  def updateCheck(applicationId: ApplicationId, checkInformation: CheckInformation): Future[Application] = {
    for {
      existing <- fetchApp(applicationId)
      savedApp <- applicationRepository.save(existing.copy(checkInformation = Some(checkInformation)))
    } yield Application(data = savedApp)
  }

  def addTermsOfUseAcceptance(applicationId: ApplicationId, acceptance: TermsOfUseAcceptance): Future[StoredApplication] = {
    for {
      updatedApp <- applicationRepository.addApplicationTermsOfUseAcceptance(applicationId, acceptance)
    } yield updatedApp
  }

  def confirmSetupComplete(applicationId: ApplicationId, requesterEmailAddress: LaxEmailAddress): Future[StoredApplication] = {
    for {
      app            <- fetchApp(applicationId)
      oldState        = app.state
      newState        = app.state.toProduction(instant())
      appWithNewState = app.copy(state = newState)
      updatedApp     <- applicationRepository.save(appWithNewState)
      stateHistory    = StateHistory(applicationId, newState.name, Actors.AppCollaborator(requesterEmailAddress), Some(oldState.name), None, app.state.updatedOn)
      _              <- stateHistoryRepository.insert(stateHistory)
    } yield updatedApp
  }

  def deleteApplication(
      applicationId: ApplicationId,
      request: Option[DeleteApplicationRequest],
      auditFunction: StoredApplication => Future[AuditResult]
    )(implicit hc: HeaderCarrier
    ): Future[ApplicationStateChange] = {
    logger.info(s"Deleting application ${applicationId.value}")

    def deleteSubscriptions(app: StoredApplication): Future[HasSucceeded] = {
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

    def sendEmailsIfRequestedByEmailAddressPresent(app: StoredApplication): Future[Any] = {
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
      _   <- notificationRepository.deleteAllByApplicationId(applicationId)
      _   <- responsibleIndividualVerificationRepository.deleteAllByApplicationId(applicationId)
      _   <- termsOfUseInvitationRepository.delete(applicationId)
      _    = auditFunction(app)
      _    = recoverAll(sendEmailsIfRequestedByEmailAddressPresent(app))
    } yield Deleted).recover {
      case _: NotFoundException => Deleted
    }
  }

  def fixCollaborator(applicationId: ApplicationId, fixCollaboratorRequest: FixCollaboratorRequest): Future[Option[StoredApplication]] = {
    applicationRepository.updateCollaboratorId(applicationId, fixCollaboratorRequest.emailAddress, fixCollaboratorRequest.userId)
  }

  def fetchByClientId(clientId: ClientId): Future[Option[Application]] = {
    applicationRepository.fetchByClientId(clientId) map {
      _.map(application => Application(data = application))
    }
  }

  def findAndRecordApplicationUsage(clientId: ClientId): Future[Option[ExtendedApplicationResponse]] = {
    timeFuture("Service Find And Record Application Usage", "application.service.findAndRecordApplicationUsage") {
      (
        for {
          app           <- OptionT(applicationRepository.findAndRecordApplicationUsage(clientId))
          subscriptions <- OptionT.liftF(subscriptionRepository.getSubscriptions(app.id))
        } yield ExtendedApplicationResponse(app, subscriptions)
      )
        .value
    }
  }

  def fetchByServerToken(serverToken: String): Future[Option[Application]] = {
    applicationRepository.fetchByServerToken(serverToken) map {
      _.map(application =>
        Application(data = application)
      )
    }
  }

  def findAndRecordServerTokenUsage(serverToken: String): Future[Option[ExtendedApplicationResponse]] = {
    timeFuture("Service Find And Record Server Token Usage", "application.service.findAndRecordServerTokenUsage") {
      (
        for {
          app           <- OptionT(applicationRepository.findAndRecordServerTokenUsage(serverToken))
          subscriptions <- OptionT.liftF(subscriptionRepository.getSubscriptions(app.id))
        } yield ExtendedApplicationResponse(app, subscriptions)
      )
        .value
    }
  }

  // TODO - introduce me
  // private def asResponse(apps: List[StoredApplication]): List[Application] = {
  //   apps.map(Application(data = _))
  // }

  private def asExtendedResponses(apps: List[StoredApplication]): Future[List[ExtendedApplicationResponse]] = {
    def asExtendedResponse(app: StoredApplication): Future[ExtendedApplicationResponse] = {
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

  def fetchAll(): Future[List[Application]] = {
    applicationRepository.fetchAll().map {
      _.map(application => Application(data = application))
    }
  }

  def fetchAllBySubscription(apiContext: ApiContext): Future[List[Application]] = {
    applicationRepository.fetchAllForContext(apiContext) map {
      _.map(application => Application(data = application))
    }
  }

  def fetchAllBySubscription(apiIdentifier: ApiIdentifier): Future[List[Application]] = {
    applicationRepository.fetchAllForApiIdentifier(apiIdentifier) map {
      _.map(application => Application(data = application))
    }
  }

  def fetchAllWithNoSubscriptions(): Future[List[Application]] = {
    applicationRepository.fetchAllWithNoSubscriptions() map {
      _.map(application => Application(data = application))
    }
  }

  import cats.data.OptionT
  import cats.implicits._

  def fetch(applicationId: ApplicationId): OptionT[Future, Application] =
    OptionT(applicationRepository.fetch(applicationId))
      .map(application => Application(data = application))

  def searchApplications(applicationSearch: ApplicationSearch): Future[PaginatedApplicationResponse] = {
    applicationRepository.searchApplications("applicationSearch")(applicationSearch).map { data =>
      PaginatedApplicationResponse(
        page = applicationSearch.pageNumber,
        pageSize = applicationSearch.pageSize,
        total = data.totals.foldLeft(0)(_ + _.total),
        matching = data.matching.foldLeft(0)(_ + _.total),
        applications = data.applications.map(application => Application(data = application))
      )
    }
  }

  private def createApp(createApplicationRequest: CreateApplicationRequest)(implicit hc: HeaderCarrier): Future[CreateApplicationResponse] = {
    // val createApplicationRequest: CreateApplicationRequest = req match {
    //   case v1: CreateApplicationRequestV1 => v1.normaliseCollaborators
    //   case v2: CreateApplicationRequestV2 => v2.normaliseCollaborators
    // }

    logger.info(s"Creating application ${createApplicationRequest.name}")

    val wso2ApplicationName = credentialGenerator.generate()

    def createInApiGateway(appData: StoredApplication): Future[HasSucceeded] = {
      if (appData.isInPreProductionOrProduction) {
        apiGatewayStore.createApplication(appData.wso2ApplicationName, appData.tokens.production.accessToken)
      } else {
        successful(HasSucceeded)
      }
    }

    def applyTotpForPrivAppsOnly(totp: Option[Totp], request: CreateApplicationRequest): CreateApplicationRequest = {
      request match {
        case v1 @ CreateApplicationRequestV1(_, priv: Access.Privileged, _, _, _, _) => v1.copy(access = priv.copy(totpIds = extractTotpId(totp)))
        case _                                                                       => request
      }
    }

    val f = for {
      _              <- createApplicationRequest.accessType match {
                          case AccessType.PRIVILEGED => upliftNamingService.assertAppHasUniqueNameAndAudit(createApplicationRequest.name, AccessType.PRIVILEGED)
                          case AccessType.ROPC       => upliftNamingService.assertAppHasUniqueNameAndAudit(createApplicationRequest.name, AccessType.ROPC)
                          case _                     => successful(())
                        }
      totp           <- generateApplicationTotp(createApplicationRequest.accessType)
      modifiedRequest = applyTotpForPrivAppsOnly(totp, createApplicationRequest)
      appData         = StoredApplication.create(modifiedRequest, wso2ApplicationName, tokenService.createEnvironmentToken(), Instant.now(clock))
      _              <- createInApiGateway(appData)
      _              <- applicationRepository.save(appData)
      _              <- createStateHistory(appData)
      _               = auditAppCreated(appData)
    } yield CreateApplicationResponse(Application(appData), extractTotpSecret(totp))

    f andThen {
      case Failure(_) =>
        apiGatewayStore.deleteApplication(wso2ApplicationName)
          .map(_ => logger.info(s"deleted application: [$wso2ApplicationName]"))
    }
  }

  private def generateApplicationTotp(accessType: AccessType)(implicit hc: HeaderCarrier): Future[Option[Totp]] = {
    accessType match {
      case AccessType.PRIVILEGED => totpConnector.generateTotp().map(Some(_))
      case _                     => Future(None)
    }
  }

  private def extractTotpId(totp: Option[Totp]): Option[TotpId] = {
    totp.map { t => TotpId(t.id) }
  }

  private def extractTotpSecret(totp: Option[Totp]): Option[CreateApplicationResponse.TotpSecret] = {
    totp.map { t => CreateApplicationResponse.TotpSecret(t.secret) }
  }

  def createStateHistory(appData: StoredApplication)(implicit hc: HeaderCarrier) = {
    val actor = appData.access.accessType match {
      case AccessType.PRIVILEGED | AccessType.ROPC => Actors.Unknown
      case _                                       => loggedInActor
    }
    insertStateHistory(appData, appData.state.name, None, actor, (a: StoredApplication) => applicationRepository.hardDelete(a.id))
  }

  private def auditAppCreated(app: StoredApplication)(implicit hc: HeaderCarrier) =
    auditService.audit(
      AppCreated,
      Map(
        "applicationId"             -> app.id.value.toString,
        "newApplicationName"        -> app.name,
        "newApplicationDescription" -> app.description.getOrElse("")
      )
    )

  private def updateApp(applicationId: ApplicationId)(applicationRequest: UpdateApplicationRequest)(implicit hc: HeaderCarrier): Future[StoredApplication] = {
    logger.info(s"Updating application ${applicationRequest.name}")

    def updatedAccess(existing: StoredApplication): Access =
      (applicationRequest.access, existing.access) match {
        case (newAccess: Access.Standard, oldAccess: Access.Standard) => newAccess.copy(overrides = oldAccess.overrides)
        case _                                                        => applicationRequest.access
      }

    def updatedApplication(existing: StoredApplication): StoredApplication =
      existing.copy(
        name = applicationRequest.name,
        normalisedName = applicationRequest.name.toLowerCase,
        description = applicationRequest.description,
        access = updatedAccess(existing)
      )

    def checkAccessType(existing: StoredApplication): Unit =
      if (existing.access.accessType != applicationRequest.access.accessType) {
        throw new ForbiddenException("Updating the access type of an application is not allowed")
      }

    for {
      existing <- fetchApp(applicationId)
      _         = checkAccessType(existing)
      savedApp <- applicationRepository.save(updatedApplication(existing))
      _         = AuditHelper.calculateAppChanges(existing, savedApp).foreach(Function.tupled(auditService.audit))
    } yield savedApp
  }

  private def fetchApp(applicationId: ApplicationId) = {
    lazy val notFoundException = new NotFoundException(s"application not found for id: ${applicationId.value}")
    applicationRepository.fetch(applicationId).flatMap {
      case None      => failed(notFoundException)
      case Some(app) => successful(app)
    }
  }

  private def insertStateHistory(
      snapshotApp: StoredApplication,
      newState: State,
      oldState: Option[State],
      actor: Actor,
      rollback: StoredApplication => Any
    ) = {
    val stateHistory = StateHistory(snapshotApp.id, newState, actor, oldState, changedAt = Instant.now(clock))
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
