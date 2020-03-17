/*
 * Copyright 2020 HM Revenue & Customs
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

import java.util.UUID

import cats.data.OptionT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.Json.toJson
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.{PRIVILEGED, ROPC}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationService, CredentialService, GatekeeperService, SubscriptionService}
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Try}

@Singleton
class ApplicationController @Inject()(val applicationService: ApplicationService,
                                      val authConnector: AuthConnector,
                                      credentialService: CredentialService,
                                      subscriptionService: SubscriptionService,
                                      config: ApplicationControllerConfig,
                                      val authConfig: AuthConfig,
                                      gatekeeperService: GatekeeperService)
                                     (implicit val ec: ExecutionContext) extends CommonController with AuthorisationWrapper {

  val applicationCacheExpiry = config.fetchApplicationTtlInSecs
  val subscriptionCacheExpiry = config.fetchSubscriptionTtlInSecs

  val apiGatewayUserAgents: List[String] = List("APIPlatformAuthorizer")

  override implicit def hc(implicit request: RequestHeader) = {
    def header(key: String) = request.headers.get(key) map (key -> _)

    val extraHeaders = List(header(LOGGED_IN_USER_NAME_HEADER), header(LOGGED_IN_USER_EMAIL_HEADER), header(SERVER_TOKEN_HEADER), header(USER_AGENT)).flatten
    super.hc.withExtraHeaders(extraHeaders: _*)
  }

  def create = requiresAuthenticationFor(PRIVILEGED, ROPC).async(BodyParsers.parse.json) { implicit request =>
    withJsonBody[CreateApplicationRequest] { application =>
      applicationService.create(application).map {
        result => Created(toJson(result))
      } recover {
        case e: ApplicationAlreadyExists =>
          Conflict(JsErrorResponse(APPLICATION_ALREADY_EXISTS, s"Application already exists with name: ${e.applicationName}"))
      } recover recovery
    }
  }

  def update(applicationId: UUID) = requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async(BodyParsers.parse.json) { implicit request =>
    withJsonBody[UpdateApplicationRequest] { application =>
      applicationService.update(applicationId, application).map { result =>
        Ok(toJson(result))
      } recover recovery
    }
  }

  def updateRateLimitTier(applicationId: UUID) = requiresAuthentication().async(BodyParsers.parse.json) { implicit request =>
    withJsonBody[UpdateRateLimitTierRequest] { updateRateLimitTierRequest =>
      Try(RateLimitTier withName updateRateLimitTierRequest.rateLimitTier.toUpperCase()) match {
        case scala.util.Success(rateLimitTier) =>
          applicationService updateRateLimitTier(applicationId, rateLimitTier) map { _ =>
            NoContent
          } recover recovery
        case Failure(_) => successful(UnprocessableEntity(
          JsErrorResponse(INVALID_REQUEST_PAYLOAD, s"'${updateRateLimitTierRequest.rateLimitTier}' is an invalid rate limit tier")))
      }
    }
  }

  def updateIpWhitelist(applicationId: UUID) = Action.async(BodyParsers.parse.json) { implicit request =>
    withJsonBody[UpdateIpWhitelistRequest] { updateIpWhitelistRequest =>
      applicationService.updateIpWhitelist(applicationId, updateIpWhitelistRequest.ipWhitelist) map { _ =>
        NoContent
      } recover recovery
    }
  }

  def updateCheck(applicationId: UUID) = requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async(BodyParsers.parse.json) {
    implicit request =>
      withJsonBody[CheckInformation] { checkInformation =>
        applicationService.updateCheck(applicationId, checkInformation).map { result =>
          Ok(toJson(result))
        } recover recovery
      }
  }

  def fetch(applicationId: UUID) = Action.async {
    handleOptionT(applicationService.fetch(applicationId))
  }

  def fetchCredentials(applicationId: UUID) = Action.async {
    handleOption(credentialService.fetchCredentials(applicationId))
  }

  def addCollaborator(applicationId: UUID) = Action.async(BodyParsers.parse.json) { implicit request =>
    withJsonBody[AddCollaboratorRequest] { collaboratorRequest =>
      applicationService.addCollaborator(applicationId, collaboratorRequest) map {
        response => Ok(toJson(response))
      } recover {
        case _: UserAlreadyExists => Conflict(JsErrorResponse(USER_ALREADY_EXISTS,
          "This email address is already registered with different role, delete and add with desired role"))

        case _: InvalidEnumException => UnprocessableEntity(JsErrorResponse(INVALID_REQUEST_PAYLOAD, "Invalid Role"))
      } recover recovery
    }
  }

  def deleteCollaborator(applicationId: UUID, email: String, admin: String, adminsToEmail: String) = {

    val adminsToEmailSet = adminsToEmail.split(",").toSet[String].map(_.trim).filter(_.nonEmpty)

    Action.async { implicit request =>
      applicationService.deleteCollaborator(applicationId, email, admin, adminsToEmailSet) map (_ => NoContent) recover {
        case _: ApplicationNeedsAdmin => Forbidden(JsErrorResponse(APPLICATION_NEEDS_ADMIN, "Application requires at least one admin"))
      } recover recovery
    }
  }

  def addClientSecret(applicationId: UUID) = Action.async(BodyParsers.parse.json) { implicit request =>
      withJsonBody[ClientSecretRequest] { secret =>
        credentialService.addClientSecret(applicationId, secret) map { token => Ok(toJson(token))
        } recover {
          case e: NotFoundException => handleNotFound(e.getMessage)
          case _: InvalidEnumException => UnprocessableEntity(JsErrorResponse(INVALID_REQUEST_PAYLOAD, "Invalid environment"))
          case _: ClientSecretsLimitExceeded => Forbidden(JsErrorResponse(CLIENT_SECRET_LIMIT_EXCEEDED, "Client secret limit has been exceeded"))
          case e => handleException(e)
        }
      }
    }

  def deleteClientSecrets(appId: java.util.UUID) = {
    Action.async(BodyParsers.parse.json) { implicit request =>
      withJsonBody[DeleteClientSecretsRequest] { secretsRequest =>
        credentialService.deleteClientSecrets(appId, secretsRequest.actorEmailAddress, secretsRequest.secrets).map(_ => NoContent) recover recovery
      }
    }
  }

  def validateCredentials: Action[JsValue] = Action.async(BodyParsers.parse.json) { implicit request =>
    withJsonBody[ValidationRequest] { vr: ValidationRequest =>
      credentialService.validateCredentials(vr).value map {
        case Some(application) => Ok(toJson(application))
        case None => Unauthorized(JsErrorResponse(INVALID_CREDENTIALS, "Invalid client id or secret"))
      } recover recovery
    }
  }

  def validateApplicationName: Action[JsValue] =
    Action.async(BodyParsers.parse.json) { implicit request =>

      withJsonBody[ApplicationNameValidationRequest] { applicationNameValidationRequest: ApplicationNameValidationRequest =>

        applicationService
          .validateApplicationName(applicationNameValidationRequest.applicationName, applicationNameValidationRequest.selfApplicationId)
          .map((result: ApplicationNameValidationResult) => {

            val json = result match {
              case Valid => Json.obj()
              case Invalid(invalidName, duplicateName) => Json.obj("errors" -> Json.obj("invalidName" -> invalidName, "duplicateName" -> duplicateName))
            }

            Ok(json)
          })

      } recover recovery
  }


  def requestUplift(id: java.util.UUID) = Action.async(BodyParsers.parse.json) { implicit request =>
    withJsonBody[UpliftRequest] { upliftRequest =>
      applicationService.requestUplift(id, upliftRequest.applicationName, upliftRequest.requestedByEmailAddress)
        .map(_ => NoContent)
    } recover {
      case _: InvalidStateTransition =>
        PreconditionFailed(JsErrorResponse(INVALID_STATE_TRANSITION, s"Application is not in state '${State.TESTING}'"))
      case e: ApplicationAlreadyExists =>
        Conflict(JsErrorResponse(APPLICATION_ALREADY_EXISTS, s"Application already exists with name: ${e.applicationName}"))
    } recover recovery
  }

  private def handleOption[T](future: Future[Option[T]])(implicit writes: Writes[T]): Future[Result] = {
    future.map {
      case Some(v) => Ok(toJson(v))
      case None => handleNotFound("No application was found")
    } recover recovery
  }

  private def handleOptionT[T](opt: OptionT[Future,T])(implicit writes: Writes[T]): Future[Result] = {
    opt.fold(handleNotFound("No application was found"))(v => Ok(toJson(v)))
      .recover(recovery)
  }

  def queryDispatcher() = Action.async { implicit request =>
    val queryBy = request.queryString.keys.toList.sorted
    val serverToken = hc.headers find (_._1 == SERVER_TOKEN_HEADER) map (_._2)

    def addHeaders(pred: Result => Boolean, headers: (String, String)*)(res: Result): Result =
      if (pred(res)) res.withHeaders(headers: _*) else res

    (queryBy, serverToken) match {
      case (_, Some(token)) =>
        fetchByServerToken(token)
          .map(addHeaders(res => res.header.status == OK || res.header.status == NOT_FOUND,
            CACHE_CONTROL -> s"max-age=$applicationCacheExpiry", VARY -> SERVER_TOKEN_HEADER))
      case ("clientId" :: _, _) =>
        fetchByClientId(request.queryString("clientId").head)
          .map(addHeaders(_.header.status == OK,
            CACHE_CONTROL -> s"max-age=$applicationCacheExpiry"))
      case ("emailAddress" :: "environment" :: _, _) =>
        fetchAllForCollaboratorAndEnvironment(request.queryString("emailAddress").head, request.queryString("environment").head)
      case ("emailAddress" :: _, _) =>
        fetchAllForCollaborator(request.queryString("emailAddress").head)
      case ("subscribesTo" :: "version" :: _, _) =>
        fetchAllBySubscriptionVersion(APIIdentifier(request.queryString("subscribesTo").head, request.queryString("version").head))
      case ("subscribesTo" :: _, _) =>
        fetchAllBySubscription(request.queryString("subscribesTo").head)
      case ("noSubscriptions" :: _, _) =>
        fetchAllWithNoSubscriptions()
      case _ => fetchAll()
    }
  }

  def searchApplications = Action.async { implicit
                                          request =>
    applicationService.searchApplications(ApplicationSearch.fromQueryString(request.queryString)).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchByServerToken(serverToken: String)(implicit hc: HeaderCarrier): Future[Result] =
    fetchAndUpdateApplication(() => applicationService.fetchByServerToken(serverToken), "No application was found for server token")

  private def fetchByClientId(clientId: String)(implicit hc: HeaderCarrier): Future[Result] =
    fetchAndUpdateApplication(() => applicationService.fetchByClientId(clientId), "No application was found")

  private def fetchAndUpdateApplication(fetchFunction: () => Future[Option[ApplicationResponse]],
                                        notFoundMessage: String)(implicit hc: HeaderCarrier): Future[Result] =
    fetchFunction().flatMap {
      case Some(application) =>
        // If request has originated from an API gateway, record usage of the Application
        hc.headers.find(_._1 == USER_AGENT).map(_._2) match {
          case Some(userAgent) if apiGatewayUserAgents.contains(userAgent) =>
            applicationService.recordApplicationUsage(application.id).map(updatedApp => Ok(toJson(updatedApp)))
          case _ => successful(Ok(toJson(application)))
        }
      case None => successful(handleNotFound(notFoundMessage))
    } recover recovery

  private def fetchAllForCollaborator(emailAddress: String) = {
    applicationService.fetchAllForCollaborator(emailAddress).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAllForCollaboratorAndEnvironment(emailAddress: String, environment: String) = {
    applicationService.fetchAllForCollaboratorAndEnvironment(emailAddress, environment).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAll() = {
    applicationService.fetchAll().map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAllBySubscription(apiContext: String) = {
    applicationService.fetchAllBySubscription(apiContext).map(apps => Ok(toJson(apps))) recover recovery
  }

  private def fetchAllBySubscriptionVersion(apiContext: APIIdentifier) = {
    applicationService.fetchAllBySubscription(apiContext).map(apps => Ok(toJson(apps))) recover recovery
  }

  def fetchAllWithNoSubscriptions() = {
    applicationService.fetchAllWithNoSubscriptions().map(apps => Ok(toJson(apps))) recover recovery
  }

  def fetchAllAPISubscriptions(): Action[AnyContent] = Action.async((request: Request[play.api.mvc.AnyContent]) =>
    subscriptionService.fetchAllSubscriptions()
      .map(subs => Ok(toJson(subs))) recover recovery
  )

  def fetchAllSubscriptions(applicationId: UUID) = Action.async { implicit request =>
    subscriptionService.fetchAllSubscriptionsForApplication(applicationId)
      .map(subs => Ok(toJson(subs))) recover recovery
  }

  def isSubscribed(id: java.util.UUID, context: String, version: String) = Action.async {
    val api = APIIdentifier(context, version)
    subscriptionService.isSubscribed(id, api) map {
      case true => Ok(toJson(api)).withHeaders(CACHE_CONTROL -> s"max-age=$subscriptionCacheExpiry")
      case false => NotFound(JsErrorResponse(SUBSCRIPTION_NOT_FOUND, s"Application $id is not subscribed to $context $version"))
    } recover recovery
  }

  def createSubscriptionForApplication(applicationId: UUID) =
    requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async(BodyParsers.parse.json) {
      implicit request =>
        withJsonBody[APIIdentifier] { api =>
          subscriptionService.createSubscriptionForApplication(applicationId, api).map(_ => NoContent) recover {
            case e: SubscriptionAlreadyExistsException => Conflict(JsErrorResponse(SUBSCRIPTION_ALREADY_EXISTS, e.getMessage))
          } recover recovery
        }
    }

  def removeSubscriptionForApplication(applicationId: UUID, context: String, version: String) = {
    requiresAuthenticationForPrivilegedOrRopcApplications(applicationId).async { implicit request =>
      subscriptionService.removeSubscriptionForApplication(applicationId, APIIdentifier(context, version)).map(_ => NoContent) recover recovery
    }
  }

  def verifyUplift(verificationCode: String) = Action.async { implicit request =>
    applicationService.verifyUplift(verificationCode) map (_ => NoContent) recover {
      case e: InvalidUpliftVerificationCode => BadRequest(e.getMessage)
    } recover recovery
  }

  def deleteApplication(id: UUID): Action[AnyContent] = (Action andThen strideAuthRefiner()).async { implicit request: OptionalStrideAuthRequest[AnyContent] =>
    def audit(app: ApplicationData): Future[AuditResult] = {
      Logger.info(s"Delete application ${app.id} - ${app.name}")
      successful(uk.gov.hmrc.play.audit.http.connector.AuditResult.Success)
    }

    def nonStrideAuthenticatedApplicationDelete(): Future[Result] = {
        if (authConfig.canDeleteApplications) {

          applicationService.fetch(id)
            .flatMap(_ => OptionT.liftF(applicationService.deleteApplication(id, None, audit)))
            .fold(handleNotFound("No application was found"))(_ => NoContent)
        } else successful(BadRequest("Cannot delete this application"))
    }


    def strideAuthenticatedApplicationDelete(deleteApplicationPayload: DeleteApplicationRequest): Future[Result] = {
      // This is audited in the GK FE
      gatekeeperService.deleteApplication(id, deleteApplicationPayload).map(_ => NoContent)
    }

    {
      if (request.isStrideAuth) {
        withJsonBodyFromAnyContent[DeleteApplicationRequest] {
          deleteApplicationPayload => strideAuthenticatedApplicationDelete(deleteApplicationPayload)
        }
      } else {
        nonStrideAuthenticatedApplicationDelete()
      }
    } recover recovery
  }
}

case class ApplicationControllerConfig(fetchApplicationTtlInSecs: Int, fetchSubscriptionTtlInSecs: Int)
