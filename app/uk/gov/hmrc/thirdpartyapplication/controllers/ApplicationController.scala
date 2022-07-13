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

package uk.gov.hmrc.thirdpartyapplication.controllers

import cats.data.OptionT
import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.controllers.UpdateIpAllowlistRequest.toIpAllowlist
import uk.gov.hmrc.thirdpartyapplication.controllers.UpdateGrantLengthRequest.toGrantLength
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.http.HeaderCarrierUtils._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.utils._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.apiplatform.modules.upliftlinks.service.UpliftLinkService
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import uk.gov.hmrc.thirdpartyapplication.config.AuthConfig

@Singleton
class ApplicationController @Inject() (
    val applicationService: ApplicationService,
    val authConnector: StrideAuthConnector,
    val authConfig: AuthConfig,
    credentialService: CredentialService,
    subscriptionService: SubscriptionService,
    config: ApplicationControllerConfig,
    gatekeeperService: GatekeeperService,
    submissionsService: SubmissionsService,
    upliftNamingService: UpliftNamingService,
    upliftLinkService: UpliftLinkService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with JsonUtils
    with StrideGatekeeperAuthorise
    with AuthorisationWrapper
    with AuthKeyRefiner
    with ApplicationLogger {

  import cats.implicits._

  val applicationCacheExpiry  = config.fetchApplicationTtlInSecs
  val subscriptionCacheExpiry = config.fetchSubscriptionTtlInSecs

  val apiGatewayUserAgent: String = "APIPlatformAuthorizer"

  private val E = EitherTHelper.make[String]

  def create = requiresAuthenticationFor(PRIVILEGED, ROPC).async(parse.json) { implicit request =>
    def onV2(createApplicationRequest: CreateApplicationRequest, fn: CreateApplicationRequestV2 => Future[HasSucceeded]): Future[HasSucceeded] =
      createApplicationRequest match {
        case _: CreateApplicationRequestV1 => successful(HasSucceeded)
        case r: CreateApplicationRequestV2 => fn(r)
      }

    def processV2(applicationId: ApplicationId)(requestV2: CreateApplicationRequestV2): Future[HasSucceeded] =
      (
        for {
          _          <- E.liftF(upliftLinkService.createUpliftLink(requestV2.sandboxApplicationId, applicationId))
          submission <- E.fromEitherF(submissionsService.create(applicationId, requestV2.requestedBy))
        } yield HasSucceeded
      )
        .getOrElseF(Future.failed(new RuntimeException("Bang")))

    withJsonBody[CreateApplicationRequest] { createApplicationRequest =>
      {
        for {
          applicationResponse <- applicationService.create(createApplicationRequest)
          applicationId        = applicationResponse.application.id
          subs                 = createApplicationRequest.anySubscriptions
          _                   <- Future.sequence(subs.map(api => subscriptionService.createSubscriptionForApplicationMinusChecks(applicationId, api)))
          _                   <- onV2(createApplicationRequest, processV2(applicationId))
        } yield Created(toJson(applicationResponse))
      } recover {
        case e: ApplicationAlreadyExists =>
          Conflict(JsErrorResponse(APPLICATION_ALREADY_EXISTS, s"Application already exists with name: ${e.applicationName}"))
      } recover recovery
    }
  }

  def update(applicationId: ApplicationId) = requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async(parse.json) { implicit request =>
    withJsonBody[UpdateApplicationRequest] { application =>
      applicationService.update(applicationId, application).map { result =>
        Ok(toJson(result))
      } recover recovery
    }
  }

  def updateIpAllowlist(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[UpdateIpAllowlistRequest] { updateIpAllowlistRequest =>
      applicationService.updateIpAllowlist(applicationId, toIpAllowlist(updateIpAllowlistRequest)) map { _ =>
        NoContent
      } recover recovery
    }
  }

  def updateGrantLength(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[UpdateGrantLengthRequest] { updatedGrantLengthRequest =>
      applicationService.updateGrantLength(applicationId, toGrantLength(updatedGrantLengthRequest)) map { _ =>
        NoContent
      } recover recovery
    }
  }

  def updateCheck(applicationId: ApplicationId) = requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async(parse.json) {
    implicit request =>
      withJsonBody[CheckInformation] { checkInformation =>
        applicationService.updateCheck(applicationId, checkInformation).map { result =>
          Ok(toJson(result))
        } recover recovery
      }
  }

  def fetch(applicationId: ApplicationId) = Action.async {
    handleOptionT(applicationService.fetch(applicationId))
  }

  def fetchCredentials(applicationId: ApplicationId) = Action.async {
    handleOption(credentialService.fetchCredentials(applicationId))
  }

  def addCollaborator(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[AddCollaboratorRequest] { collaboratorRequest =>
      applicationService.addCollaborator(applicationId, collaboratorRequest) map {
        response => Ok(toJson(response))
      } recover {
        case _: UserAlreadyExists => Conflict(JsErrorResponse(USER_ALREADY_EXISTS, "This email address is already registered with different role, delete and add with desired role"))

        case _: InvalidEnumException => UnprocessableEntity(JsErrorResponse(INVALID_REQUEST_PAYLOAD, "Invalid Role"))
      } recover recovery
    }
  }

  def deleteCollaborator(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[DeleteCollaboratorRequest] { dcRequest =>
      applicationService.deleteCollaborator(applicationId, dcRequest.email, dcRequest.adminsToEmail, dcRequest.notifyCollaborator) map (_ => NoContent) recover {
        case _: ApplicationNeedsAdmin => Forbidden(JsErrorResponse(APPLICATION_NEEDS_ADMIN, "Application requires at least one admin"))
      } recover recovery
    }
  }

  def fixCollaborator(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[FixCollaboratorRequest] { fixCollaboratorRequest =>
      applicationService.fixCollaborator(applicationId, fixCollaboratorRequest).map {
        case Some(_) => Ok
        case None    => Conflict
      } recover recovery
    }
  }

  def addClientSecret(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[ClientSecretRequest] { secret =>
      credentialService.addClientSecret(applicationId, secret) map { token => Ok(toJson(token)) } recover {
        case e: NotFoundException          => handleNotFound(e.getMessage)
        case _: InvalidEnumException       => UnprocessableEntity(JsErrorResponse(INVALID_REQUEST_PAYLOAD, "Invalid environment"))
        case _: ClientSecretsLimitExceeded => Forbidden(JsErrorResponse(CLIENT_SECRET_LIMIT_EXCEEDED, "Client secret limit has been exceeded"))
        case e                             => handleException(e)
      }
    }
  }

  def deleteClientSecret(applicationId: ApplicationId, clientSecretId: String) = {
    Action.async(parse.json) { implicit request =>
      withJsonBody[DeleteClientSecretRequest] { deleteClientSecretRequest =>
        credentialService.deleteClientSecret(applicationId, clientSecretId, deleteClientSecretRequest.actorEmailAddress).map(_ => NoContent) recover recovery
      }
    }
  }

  def validateCredentials: Action[JsValue] = Action.async(parse.json) { implicit request =>
    withJsonBody[ValidationRequest] { vr: ValidationRequest =>
      credentialService.validateCredentials(vr).value map {
        case Some(application) => Ok(toJson(application))
        case None              => Unauthorized(JsErrorResponse(INVALID_CREDENTIALS, "Invalid client id or secret"))
      } recover recovery
    }
  }

  def validateApplicationName: Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      withJsonBody[ApplicationNameValidationRequest] { applicationNameValidationRequest: ApplicationNameValidationRequest =>
        upliftNamingService
          .validateApplicationName(applicationNameValidationRequest.applicationName, applicationNameValidationRequest.selfApplicationId)
          .map((result: ApplicationNameValidationResult) => {

            val json = result match {
              case ValidName     => Json.obj()
              case InvalidName   => Json.obj("errors" -> Json.obj("invalidName" -> true, "duplicateName" -> false))
              case DuplicateName => Json.obj("errors" -> Json.obj("invalidName" -> false, "duplicateName" -> true))
            }

            Ok(json)
          })

      } recover recovery
    }

  private def handleOption[T](future: Future[Option[T]])(implicit writes: Writes[T]): Future[Result] = {
    future.map {
      case Some(v) => Ok(toJson(v))
      case None    => handleNotFound("No application was found")
    } recover recovery
  }

  private def handleOptionT[T](opt: OptionT[Future, T])(implicit writes: Writes[T]): Future[Result] = {
    opt.fold(handleNotFound("No application was found"))(v => Ok(toJson(v)))
      .recover(recovery)
  }

  def queryDispatcher() = Action.async { implicit request =>
    val queryBy     = request.queryString.keys.toList.sorted
    val serverToken = hc.valueOf(SERVER_TOKEN_HEADER)

    def addHeaders(pred: Result => Boolean, headers: (String, String)*)(res: Result): Result =
      if (pred(res)) res.withHeaders(headers: _*) else res

    (queryBy, serverToken) match {
      case (_, Some(token))                      =>
        fetchByServerToken(token)
          .map(addHeaders(res => res.header.status == OK || res.header.status == NOT_FOUND, CACHE_CONTROL -> s"max-age=$applicationCacheExpiry", VARY -> SERVER_TOKEN_HEADER))
      case ("clientId" :: _, _)                  =>
        val clientId = ClientId(request.queryString("clientId").head)
        fetchByClientId(clientId)
          .map(addHeaders(_.header.status == OK, CACHE_CONTROL -> s"max-age=$applicationCacheExpiry"))
      case ("environment" :: "userId" :: _, _)   =>
        val rawQueryParameter = request.queryString("userId")
        val ouserId           = UserId.fromString(rawQueryParameter.head)

        ouserId.fold(
          successful(BadRequest(JsErrorResponse(BAD_QUERY_PARAMETER, s"UserId ${rawQueryParameter.head} is not a valid user Id")))
        )(userId => fetchAllForUserIdAndEnvironment(userId, request.queryString("environment").head))
      case ("subscribesTo" :: "version" :: _, _) =>
        val context = ApiContext(request.queryString("subscribesTo").head)
        val version = ApiVersion(request.queryString("version").head)
        fetchAllBySubscriptionVersion(ApiIdentifier(context, version))
      case ("subscribesTo" :: _, _)              =>
        val context = ApiContext(request.queryString("subscribesTo").head)
        fetchAllBySubscription(context)
      case ("noSubscriptions" :: _, _)           =>
        fetchAllWithNoSubscriptions()
      case _                                     => fetchAll()
    }
  }

  def searchApplications = Action.async { implicit request =>
    Try(ApplicationSearch.fromQueryString(request.queryString)) match {
      case Success(applicationSearch) => applicationService.searchApplications(applicationSearch).map(apps => Ok(toJson(apps))) recover recovery
      case Failure(e)                 => successful(BadRequest(JsErrorResponse(BAD_QUERY_PARAMETER, e.getMessage)))
    }
  }

  private def fetchByServerToken(serverToken: String)(implicit hc: HeaderCarrier): Future[Result] =
    fetchAndUpdateApplication(
      () => applicationService.fetchByServerToken(serverToken),
      appId => applicationService.recordServerTokenUsage(appId),
      "No application was found for server token"
    )

  private def fetchByClientId(clientId: ClientId)(implicit hc: HeaderCarrier): Future[Result] =
    fetchAndUpdateApplication(
      () => applicationService.fetchByClientId(clientId),
      appId => applicationService.recordApplicationUsage(appId),
      "No application was found for client id"
    )

  private def fetchAndUpdateApplication(
      fetchFunction: () => Future[Option[ApplicationResponse]],
      updateFunction: ApplicationId => Future[ExtendedApplicationResponse],
      notFoundMessage: String
    )(implicit hc: HeaderCarrier
    ): Future[Result] =
    fetchFunction().flatMap {
      case Some(application) =>
        // If request has originated from an API gateway, record usage of the Application
        hc.extraHeaders
          .find(_._1 == INTERNAL_USER_AGENT)
          .map(_._2)
          .map(_.split(","))
          .flatMap(_.find(_ == apiGatewayUserAgent))
          .fold(successful(Ok(toJson(application))))(_ => updateFunction(application.id).map(updatedApp => Ok(toJson(updatedApp))))
      case None              => successful(handleNotFound(notFoundMessage))
    } recover recovery

  def fetchAllForCollaborator(userId: UserId) = Action.async {
    applicationService.fetchAllForCollaborator(userId).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAllForUserIdAndEnvironment(userId: UserId, environment: String) = {
    applicationService.fetchAllForUserIdAndEnvironment(userId, environment).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAll() = {
    applicationService.fetchAll().map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAllBySubscription(apiContext: ApiContext) = {
    applicationService.fetchAllBySubscription(apiContext).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAllBySubscriptionVersion(apiContext: ApiIdentifier) = {
    applicationService.fetchAllBySubscription(apiContext).map(apps => Ok(toJson(apps))) recover recovery
  }

  def fetchAllWithNoSubscriptions() = {
    applicationService.fetchAllWithNoSubscriptions().map(apps => Ok(toJson(apps))) recover recovery
  }

  def fetchAllAPISubscriptions(): Action[AnyContent] = Action.async((request: Request[play.api.mvc.AnyContent]) =>
    subscriptionService.fetchAllSubscriptions()
      .map(subs => Ok(toJson(subs))) recover recovery
  )

  def fetchAllSubscriptions(applicationId: ApplicationId) = Action.async { _ =>
    subscriptionService.fetchAllSubscriptionsForApplication(applicationId)
      .map(subs => Ok(toJson(subs))) recover recovery
  }

  def isSubscribed(applicationId: ApplicationId, context: ApiContext, version: ApiVersion) = Action.async {
    val api = ApiIdentifier(context, version)
    subscriptionService.isSubscribed(applicationId, api) map {
      case true  => Ok(toJson(api)).withHeaders(CACHE_CONTROL -> s"max-age=$subscriptionCacheExpiry")
      case false => NotFound(JsErrorResponse(SUBSCRIPTION_NOT_FOUND, s"Application ${applicationId.value} is not subscribed to $context $version"))
    } recover recovery
  }

  def createSubscriptionForApplication(applicationId: ApplicationId) =
    requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async(parse.json) {
      implicit request =>
        withJsonBody[ApiIdentifier] { api =>
          subscriptionService.createSubscriptionForApplicationMinusChecks(applicationId, api).map(_ => NoContent) recover recovery
        }
    }

  def removeSubscriptionForApplication(applicationId: ApplicationId, context: ApiContext, version: ApiVersion) = {
    requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async { implicit request =>
      subscriptionService.removeSubscriptionForApplication(applicationId, ApiIdentifier(context, version)).map(_ => NoContent) recover recovery
    }
  }

  def deleteApplication(id: ApplicationId): Action[AnyContent] = (Action andThen authKeyRefiner).async { implicit request: MaybeMatchesAuthorisationKeyRequest[AnyContent] =>
    def audit(app: ApplicationData): Future[AuditResult] = {
      logger.info(s"Delete application ${app.id.value} - ${app.name}")
      successful(uk.gov.hmrc.play.audit.http.connector.AuditResult.Success)
    }

    val ET              = EitherTHelper.make[Result]
    lazy val badRequest = BadRequest("Cannot delete this application")

    (
      for {
        app <- ET.fromOptionF(applicationService.fetch(id).value, handleNotFound("No application was found"))
        _   <- ET.cond(authConfig.canDeleteApplications || request.matchesAuthorisationKey || !app.state.isInPreProductionOrProduction, app, badRequest)
        _   <- ET.liftF(applicationService.deleteApplication(id, None, audit))
      } yield NoContent
    )
      .fold(identity, identity)
  }

  def confirmSetupComplete(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[ConfirmSetupCompleteRequest] { request =>
      applicationService.confirmSetupComplete(applicationId, request.requesterEmailAddress) map { _ =>
        NoContent
      } recover recovery
    }
  }
}

case class ApplicationControllerConfig(fetchApplicationTtlInSecs: Int, fetchSubscriptionTtlInSecs: Int)
