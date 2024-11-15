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

package uk.gov.hmrc.thirdpartyapplication.controllers

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import cats.data.OptionT

import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.CheckInformation
import uk.gov.hmrc.apiplatform.modules.applications.core.interface.models.{CreateApplicationRequest, CreateApplicationRequestV1, CreateApplicationRequestV2}
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationService
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.apiplatform.modules.upliftlinks.service.UpliftLinkService
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.controllers.actions.{ApplicationTypeAuthorisationActions, AuthKeyRefiner}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.util.http.HeaderCarrierUtils._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

@Singleton
class ApplicationController @Inject() (
    val strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService,
    val authControlConfig: AuthControlConfig,
    val applicationService: ApplicationService,
    credentialService: CredentialService,
    subscriptionService: SubscriptionService,
    config: ApplicationControllerConfig,
    submissionsService: SubmissionsService,
    upliftNamingService: UpliftNamingService,
    upliftLinkService: UpliftLinkService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with JsonUtils
    with ApplicationTypeAuthorisationActions
    with AuthKeyRefiner
    with ApplicationLogger {

  import cats.implicits._

  val applicationCacheExpiry  = config.fetchApplicationTtlInSecs
  val subscriptionCacheExpiry = config.fetchSubscriptionTtlInSecs

  val apiGatewayUserAgent: String = "APIPlatformAuthorizer"

  private val E = EitherTHelper.make[String]

  def create = requiresAuthenticationFor(AccessType.PRIVILEGED, AccessType.ROPC).async(parse.json) { implicit request =>
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
          _                   <- Future.sequence(subs.map(api =>
                                   subscriptionService.updateApplicationForApiSubscription(
                                     applicationId,
                                     applicationResponse.application.name,
                                     applicationResponse.application.collaborators,
                                     api
                                   )
                                 ))
          _                   <- onV2(createApplicationRequest, processV2(applicationId))
        } yield Created(toJson(applicationResponse))
      } recover {
        case e: ApplicationAlreadyExists   =>
          Conflict(JsErrorResponse(APPLICATION_ALREADY_EXISTS, s"Application already exists with name: ${e.applicationName}"))
        case e: FailedToSubscribeException =>
          BadRequest(JsErrorResponse(FAILED_TO_SUBSCRIBE, s"${e.getMessage}"))
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

  def fixCollaborator(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    withJsonBody[FixCollaboratorRequest] { fixCollaboratorRequest =>
      applicationService.fixCollaborator(applicationId, fixCollaboratorRequest).map {
        case Some(_) => Ok
        case None    => Conflict
      } recover recovery
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
        val ouserId           = UserId.apply(rawQueryParameter.head)

        ouserId.fold(
          successful(BadRequest(JsErrorResponse(BAD_QUERY_PARAMETER, s"UserId ${rawQueryParameter.head} is not a valid user Id")))
        )(userId => fetchAllForUserIdAndEnvironment(userId, Environment.unsafeApply(request.queryString("environment").head)))
      case ("subscribesTo" :: "version" :: _, _) =>
        val context = ApiContext(request.queryString("subscribesTo").head)
        val version = ApiVersionNbr(request.queryString("version").head)
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

  private def hasGatewayUserAgent(implicit hc: HeaderCarrier): Boolean = {
    hc.extraHeaders
      .find(_._1 == INTERNAL_USER_AGENT)
      .map(_._2)
      .map(_.split(","))
      .flatMap(_.find(_ == apiGatewayUserAgent))
      .isDefined
  }

  private def asJsonResult[T](notFoundMessage: String)(maybeApplication: Option[T])(implicit writes: Writes[T]): Result = {
    maybeApplication.fold(handleNotFound(notFoundMessage))(t => Ok(toJson(t)))
  }

  private def fetchByServerToken(serverToken: String)(implicit hc: HeaderCarrier): Future[Result] = {
    lazy val notFoundMessage = "No application was found for server token"

    // If request has originated from an API gateway, record usage of the Application
    (
      if (hasGatewayUserAgent) {
        applicationService.findAndRecordServerTokenUsage(serverToken).map(asJsonResult(notFoundMessage))
      } else {
        applicationService.fetchByServerToken(serverToken).map(asJsonResult(notFoundMessage))
      }
    ) recover recovery
  }

  private def fetchByClientId(clientId: ClientId)(implicit hc: HeaderCarrier): Future[Result] = {
    lazy val notFoundMessage = "No application was found for client id"

    // If request has originated from an API gateway, record usage of the Application
    (
      if (hasGatewayUserAgent) {
        applicationService.findAndRecordApplicationUsage(clientId).map(asJsonResult(notFoundMessage))
      } else {
        applicationService.fetchByClientId(clientId).map(asJsonResult(notFoundMessage))
      }
    ) recover recovery
  }

  def fetchAllForCollaborators(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    withJsonBody[CollaboratorUserIds] { request =>
      applicationService.fetchAllForCollaborators(request.userIds).map(apps => Ok(toJson(apps))) recover recovery
    }
  }

  def fetchAllForCollaborator(userId: UserId) = Action.async {
    applicationService.fetchAllForCollaborator(userId, false).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAllForUserIdAndEnvironment(userId: UserId, environment: Environment) = {
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

  def isSubscribed(applicationId: ApplicationId, context: ApiContext, version: ApiVersionNbr) = Action.async {
    val api = ApiIdentifier(context, version)
    subscriptionService.isSubscribed(applicationId, api) map {
      case true  => Ok(toJson(api)).withHeaders(CACHE_CONTROL -> s"max-age=$subscriptionCacheExpiry")
      case false => NotFound(JsErrorResponse(SUBSCRIPTION_NOT_FOUND, s"Application ${applicationId} is not subscribed to $context $version"))
    } recover recovery
  }

  def deleteApplication(id: ApplicationId): Action[AnyContent] = (Action andThen authKeyRefiner).async { implicit request: MaybeMatchesAuthorisationKeyRequest[AnyContent] =>
    def audit(app: StoredApplication): Future[AuditResult] = {
      logger.info(s"Delete application ${app.id.value} - ${app.name}")
      successful(uk.gov.hmrc.play.audit.http.connector.AuditResult.Success)
    }

    val ET              = EitherTHelper.make[Result]
    lazy val badRequest = BadRequest("Cannot delete this application")

    (
      for {
        app <- ET.fromOptionF(applicationService.fetch(id).value, handleNotFound("No application was found"))
        _   <- ET.cond(authControlConfig.canDeleteApplications || request.matchesAuthorisationKey || !app.isInPreProductionOrProduction, app, badRequest)
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
