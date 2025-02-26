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

import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.{apply => _, _}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import cats.data.OptionT
import org.apache.pekko.actor.ActorSystem

import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.mongo.lock.{LockRepository, LockService}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress, _}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models._
import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.{DeleteApplicationRequest, FixCollaboratorRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, Deleted}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{PaginatedApplicationData, StoredApplication}
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
        org.apache.pekko.pattern.after(Duration(3, TimeUnit.SECONDS), using = system.scheduler) {
          create(application)
        }
    }
  }

  def updateCheck(applicationId: ApplicationId, checkInformation: CheckInformation): Future[ApplicationWithCollaborators] = {
    for {
      existing <- fetchApp(applicationId)
      savedApp <- applicationRepository.save(existing.copy(checkInformation = Some(checkInformation)))
    } yield StoredApplication.asApplication(savedApp)
  }

  def confirmSetupComplete(applicationId: ApplicationId, requesterEmailAddress: LaxEmailAddress): Future[StoredApplication] = {
    for {
      app            <- fetchApp(applicationId)
      oldState        = app.state
      newState        = app.state.toProduction(instant())
      appWithNewState = app.withState(newState)
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
    logger.info(s"Deleting application ${applicationId}")

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

  def fetchByClientId(clientId: ClientId): Future[Option[ApplicationWithCollaborators]] = {
    applicationRepository.fetchByClientId(clientId) map {
      _.map(application => StoredApplication.asApplication(application))
    }
  }

  def findAndRecordApplicationUsage(clientId: ClientId): Future[Option[(ApplicationWithSubscriptions, String)]] = {
    timeFuture("Service Find And Record Application Usage", "application.service.findAndRecordApplicationUsage") {
      (
        for {
          app           <- OptionT(applicationRepository.findAndRecordApplicationUsage(clientId))
          serverToken    = app.tokens.production.accessToken
          subscriptions <- OptionT.liftF(subscriptionRepository.getSubscriptions(app.id))
          result         = StoredApplication.asApplication(app).withSubscriptions(subscriptions.toSet)
        } yield (result, serverToken)
      )
        .value
    }
  }

  def fetchByServerToken(serverToken: String): Future[Option[ApplicationWithCollaborators]] = {
    applicationRepository.fetchByServerToken(serverToken) map {
      _.map(application =>
        StoredApplication.asApplication(application)
      )
    }
  }

  def findAndRecordServerTokenUsage(serverToken: String): Future[Option[(ApplicationWithSubscriptions, String)]] = {
    timeFuture("Service Find And Record Server Token Usage", "application.service.findAndRecordServerTokenUsage") {
      (
        for {
          app           <- OptionT(applicationRepository.findAndRecordServerTokenUsage(serverToken))
          // Unlike findAndRecordApplicationUsage(clientId), in this method the serverToken is provided as input and is used as a search term for accessToken
          // so it's not necessary create a variable here, such as serverToken = app.tokens.production.accessToken
          subscriptions <- OptionT.liftF(subscriptionRepository.getSubscriptions(app.id))
          result         = StoredApplication.asApplication(app).withSubscriptions(subscriptions.toSet)
        } yield (result, serverToken)
      )
        .value
    }
  }

  // TODO - introduce me
  // private def asResponse(apps: List[StoredApplication]): List[Application] = {
  //   apps.map(Application(data = _))
  // }

  private def asExtendedResponses(apps: List[StoredApplication]): Future[List[ApplicationWithSubscriptions]] = {
    def asExtendedResponse(app: StoredApplication): Future[ApplicationWithSubscriptions] = {
      subscriptionRepository.getSubscriptions(app.id).map(subscriptions => StoredApplication.asApplication(app).withSubscriptions(subscriptions.toSet))
    }

    Future.sequence(apps.map(asExtendedResponse))
  }

  def fetchAllForCollaborators(userIds: List[UserId]): Future[List[ApplicationWithCollaborators]] = {
    Future.sequence(
      userIds.map(applicationRepository.fetchAllForUserId(_, false).map(_.toList))
    ).map(_.foldLeft(List[StoredApplication]())(_ ++ _)).map {
      _.map(application => StoredApplication.asApplication(application))
    }
      .map(_.distinct)
  }

  def fetchAllForCollaborator(userId: UserId, includeDeleted: Boolean): Future[List[ApplicationWithSubscriptions]] = {
    applicationRepository.fetchAllForUserId(userId, includeDeleted).flatMap(x => asExtendedResponses(x.toList))
  }

  def fetchAllForUserIdAndEnvironment(userId: UserId, environment: Environment): Future[List[ApplicationWithSubscriptions]] = {
    applicationRepository.fetchAllForUserIdAndEnvironment(userId, environment)
      .flatMap(x => asExtendedResponses(x.toList))
  }

  def fetchAll(): Future[List[ApplicationWithCollaborators]] = {
    applicationRepository.fetchAll().map {
      _.map(application => StoredApplication.asApplication(application))
    }
  }

  def fetchAllBySubscription(apiContext: ApiContext): Future[List[ApplicationWithCollaborators]] = {
    applicationRepository.fetchAllForContext(apiContext) map {
      _.map(application => StoredApplication.asApplication(application))
    }
  }

  def fetchAllBySubscription(apiIdentifier: ApiIdentifier): Future[List[ApplicationWithCollaborators]] = {
    applicationRepository.fetchAllForApiIdentifier(apiIdentifier) map {
      _.map(application => StoredApplication.asApplication(application))
    }
  }

  def fetchAllWithNoSubscriptions(): Future[List[ApplicationWithCollaborators]] = {
    applicationRepository.fetchAllWithNoSubscriptions() map {
      _.map(application => StoredApplication.asApplication(application))
    }
  }

  import cats.data.OptionT
  import cats.implicits._

  def fetch(applicationId: ApplicationId): OptionT[Future, ApplicationWithCollaborators] =
    OptionT(applicationRepository.fetch(applicationId))
      .map(application => StoredApplication.asApplication(application))

  def searchApplications(applicationSearch: ApplicationSearch): Future[PaginatedApplications] = {

    def buildApplication(storedApplication: StoredApplication, stateHistory: Option[StateHistory]) = {
      val partApp = StoredApplication.asApplication(storedApplication)
      partApp.modify(_.copy(lastActionActor = stateHistory.map(sh => ActorType.actorType(sh.actor)).getOrElse(ActorType.UNKNOWN)))
    }

    val applicationsResponse: Future[PaginatedApplicationData] = applicationRepository.searchApplications("applicationSearch")(applicationSearch)
    val appHistory: Future[List[StateHistory]]                 = {
      applicationsResponse.map(data => data.applications.map(app => app.id)).flatMap(ar => stateHistoryRepository.fetchDeletedByApplicationIds(ar))
    }

    applicationsResponse.zipWith(appHistory) {
      case (data, appHistory) => PaginatedApplications(
          page = applicationSearch.pageNumber,
          pageSize = applicationSearch.pageSize,
          total = data.totals.foldLeft(0)(_ + _.total),
          matching = data.matching.foldLeft(0)(_ + _.total),
          applications = data.applications.map(app => buildApplication(app, appHistory.find(ah => ah.applicationId == app.id)))
        )
    }
  }

  private def createApp(createApplicationRequest: CreateApplicationRequest)(implicit hc: HeaderCarrier): Future[CreateApplicationResponse] = {
    logger.info(s"Creating application ${createApplicationRequest.name}")

    val wso2ApplicationName = credentialGenerator.generate()

    def createInApiGateway(appData: StoredApplication): Future[HasSucceeded] = {
      if (appData.isInPreProductionOrProduction) {
        apiGatewayStore.createApplication(appData.wso2ApplicationName, appData.tokens.production.accessToken)
      } else {
        successful(HasSucceeded)
      }
    }

    def applyTotpForPrivAppsOnly(totp: Option[Totp], app: StoredApplication): StoredApplication = {
      val replaceAccess = app.access match {
        case access: Access.Privileged => access.copy(totpIds = extractTotpId(totp))
        case access                    => access
      }
      app.copy(access = replaceAccess)
    }

    val f = for {
      _       <- createApplicationRequest.accessType match {
                   case AccessType.PRIVILEGED => upliftNamingService.assertAppHasUniqueNameAndAudit(createApplicationRequest.name.value, AccessType.PRIVILEGED)
                   case AccessType.ROPC       => upliftNamingService.assertAppHasUniqueNameAndAudit(createApplicationRequest.name.value, AccessType.ROPC)
                   case _                     => successful(())
                 }
      basicApp = StoredApplication.create(createApplicationRequest, wso2ApplicationName, tokenService.createEnvironmentToken(), instant())
      totp    <- generateApplicationTotp(createApplicationRequest.accessType)
      appData  = applyTotpForPrivAppsOnly(totp, basicApp)
      _       <- createInApiGateway(appData)
      _       <- applicationRepository.save(appData)
      _       <- createStateHistory(appData)
      _        = auditAppCreated(appData)
    } yield CreateApplicationResponse(StoredApplication.asApplication(appData), extractTotpSecret(totp))

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
        "applicationId"             -> app.id.toString,
        "newApplicationName"        -> app.name.value,
        "newApplicationDescription" -> app.description.getOrElse("")
      )
    )

  private def fetchApp(applicationId: ApplicationId) = {
    lazy val notFoundException = new NotFoundException(s"application not found for id: ${applicationId}")
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
    val stateHistory = StateHistory(snapshotApp.id, newState, actor, oldState, changedAt = instant())
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
