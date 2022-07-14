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

import java.nio.charset.StandardCharsets
import java.util.Base64

import cats.data.OptionT
import cats.implicits._
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.retrieve.EmptyRetrieval
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode.APPLICATION_NOT_FOUND
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.Future.successful
import scala.util.Try
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.apiplatform.modules.gkauth.connectors.StrideAuthConnector
import uk.gov.hmrc.thirdpartyapplication.config.AuthConfig
import uk.gov.hmrc.apiplatform.modules.gkauth.domain.models.StrideAuthRoles

trait StrideGatekeeperAuthorise {
  self: BackendController with JsonUtils =>

  def authConfig: AuthConfig
  def strideAuthRoles: StrideAuthRoles
  def strideAuthConnector: StrideAuthConnector
  implicit def ec: ExecutionContext

  def authenticate[A](input: Request[A]): Future[None.type] = {
    if (authConfig.enabled) {
      implicit val hc               = HeaderCarrierConverter.fromRequest(input)
      val hasAnyGatekeeperEnrolment = Enrolment(strideAuthRoles.userRole) or Enrolment(strideAuthRoles.superUserRole) or Enrolment(strideAuthRoles.adminRole)
      strideAuthConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval).map(_ => None)
    } else {
      Future.successful(None)
    }
  }
}

case class MaybeMatchesAuthorisationKeyRequest[A](matchesAuthorisationKey: Boolean, request: Request[A]) extends WrappedRequest[A](request)

trait AuthKeyRefiner {
  self: BaseController =>
  
  def authConfig: AuthConfig

  def authKeyRefiner(implicit ec: ExecutionContext): ActionRefiner[Request, MaybeMatchesAuthorisationKeyRequest] =
    new ActionRefiner[Request, MaybeMatchesAuthorisationKeyRequest] {

      override protected def executionContext: ExecutionContext = ec

      def refine[A](request: Request[A]): Future[Either[Result, MaybeMatchesAuthorisationKeyRequest[A]]] = {
        def matchesAuthorisationKey: Boolean = {
          def base64Decode(stringToDecode: String): Try[String] = Try(new String(Base64.getDecoder.decode(stringToDecode), StandardCharsets.UTF_8))

          request.headers.get(AUTHORIZATION) match {
            case Some(authHeader) => println("HDR: "+authHeader+" vs "+authConfig.authorisationKey); base64Decode(authHeader).map(_ == authConfig.authorisationKey).getOrElse(false)
            case _                => false
          }
        }

        println("Enabled "+authConfig.enabled)
        println("Matches "+matchesAuthorisationKey)
        println("Header " + request.headers.hasHeader(AUTHORIZATION))

        val authKeyCheck = authConfig.enabled && request.headers.hasHeader(AUTHORIZATION) && matchesAuthorisationKey
        println("Auth key check "+authKeyCheck)
        (MaybeMatchesAuthorisationKeyRequest[A](authKeyCheck, request)).asRight[Result].pure[Future]
      }
    }
}

trait AuthorisationWrapper {
  self: BaseController with StrideGatekeeperAuthorise =>

  def applicationService: ApplicationService

  private abstract class ApplicationTypeFilter(toMatchAccessTypes: List[AccessType], failOnAccessTypeMismatch: Boolean)(implicit ec: ExecutionContext)
      extends ActionFilter[Request] {
    def executionContext = ec

    lazy val FAILED_ACCESS_TYPE = successful(Some(Results.Forbidden(JsErrorResponse(APPLICATION_NOT_FOUND, "application access type mismatch"))))

    def localRecovery: PartialFunction[Throwable, Option[Result]] = {
      case e: NotFoundException => Some(Results.NotFound(JsErrorResponse(APPLICATION_NOT_FOUND, e.getMessage)))
    }

    def filter[A](request: Request[A]): Future[Option[Result]] =
      deriveAccessType(request) flatMap {
        case Some(accessType) if toMatchAccessTypes.contains(accessType) => authenticate(request)
        case Some(_) if failOnAccessTypeMismatch                         => FAILED_ACCESS_TYPE
        case _                                                           => successful(None)
      } recover localRecovery

    protected def deriveAccessType[A](request: Request[A]): Future[Option[AccessType]]
  }


  private case class PayloadBasedApplicationTypeFilter(accessTypes: List[AccessType]) extends ApplicationTypeFilter(accessTypes, false) {

    final protected def deriveAccessType[A](request: Request[A]) =
      Future.successful((Json.parse(request.body.toString) \ "access" \ "accessType").asOpt[AccessType])
  }

  private case class RepositoryBasedApplicationTypeFilter(applicationId: ApplicationId, toMatchAccessTypes: List[AccessType], failOnAccessTypeMismatch: Boolean)
      extends ApplicationTypeFilter(toMatchAccessTypes, failOnAccessTypeMismatch) {

    private def error[A](e: Exception): OptionT[Future, A] = {
      OptionT.liftF(Future.failed(e))
    }

    final protected def deriveAccessType[A](request: Request[A]): Future[Option[AccessType]] =
      applicationService.fetch(applicationId)
        .map(app => app.access.accessType)
        .orElse(error(new NotFoundException(s"application ${applicationId.value} doesn't exist")))
        .value
  }


  def requiresAuthenticationFor(accessTypes: AccessType*): ActionBuilder[Request, AnyContent] =
    Action andThen PayloadBasedApplicationTypeFilter(accessTypes.toList)

  def requiresAuthenticationForStandardApplications(applicationId: ApplicationId): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(applicationId, List(STANDARD), true)

  def requiresAuthenticationForPrivilegedOrRopcApplications(applicationId: ApplicationId): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(applicationId, List(PRIVILEGED, ROPC), false)

}
