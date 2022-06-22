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
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.http.HeaderCarrierConverter
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode.APPLICATION_NOT_FOUND
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.Future.successful
import scala.util.Try
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

trait AuthorisationWrapper {
  self: BaseController =>

  implicit def ec: ExecutionContext

  def authConnector: AuthConnector
  def applicationService: ApplicationService
  def authConfig: AuthConnector.Config

  def requiresAuthentication(): ActionBuilder[Request, AnyContent] = Action andThen authenticationAction

  case class OptionalStrideAuthRequest[A](isStrideAuth: Boolean, matchesAuthorisationKey: Boolean, request: Request[A]) extends WrappedRequest[A](request)

  def strideAuthRefiner(implicit ec: ExecutionContext): ActionRefiner[Request, OptionalStrideAuthRequest] =
    new ActionRefiner[Request, OptionalStrideAuthRequest] {

      def refine[A](request: Request[A]): Future[Either[Result, OptionalStrideAuthRequest[A]]] = {
        def matchesAuthorisationKey: Boolean = {
          def base64Decode(stringToDecode: String): Try[String] = Try(new String(Base64.getDecoder.decode(stringToDecode), StandardCharsets.UTF_8))

          request.headers.get("Authorization") match {
            case Some(authHeader) => base64Decode(authHeader).map(_ == authConfig.authorisationKey).getOrElse(false)
            case _                => false
          }
        }

        val strideAuthSuccess =
          if (authConfig.enabled && request.headers.hasHeader(AUTHORIZATION)) {
            if (matchesAuthorisationKey) {
              Future.successful(OptionalStrideAuthRequest[A](isStrideAuth = false, true, request))
            } else {
              implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)
              val hasAnyGatekeeperEnrolment  = Enrolment(authConfig.userRole) or Enrolment(authConfig.superUserRole) or Enrolment(authConfig.adminRole)
              authConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval).map(_ => OptionalStrideAuthRequest[A](isStrideAuth = true, false, request))
            }
          } else {
            Future.successful(OptionalStrideAuthRequest[A](isStrideAuth = false, false, request))
          }

        strideAuthSuccess.flatMap(strideAuthRequest => {
          Future.successful(Right[Result, OptionalStrideAuthRequest[A]](strideAuthRequest))
        })
      }

      override protected def executionContext: ExecutionContext = ec
    }

  def requiresAuthenticationFor(accessTypes: AccessType*): ActionBuilder[Request, AnyContent] =
    Action andThen PayloadBasedApplicationTypeFilter(accessTypes.toList)

  def requiresAuthenticationFor(uuid: ApplicationId, accessTypes: AccessType*): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, accessTypes.toList, false)

  def requiresAuthenticationForStandardApplications(uuid: ApplicationId): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, List(STANDARD), true)

  def requiresAuthenticationForPrivilegedOrRopcApplications(applicationId: ApplicationId): ActionBuilder[Request, AnyContent] =
    Action andThen RepositoryBasedApplicationTypeFilter(applicationId, List(PRIVILEGED, ROPC), false)

  private def authenticate[A](input: Request[A]): Future[Option[Result]] = {
    if (authConfig.enabled) {
      implicit val hc               = HeaderCarrierConverter.fromRequest(input)
      val hasAnyGatekeeperEnrolment = Enrolment(authConfig.userRole) or Enrolment(authConfig.superUserRole) or Enrolment(authConfig.adminRole)
      authConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval).map { _ => None }
    } else {
      Future.successful(None)
    }
  }

  private def authenticationAction(implicit ec: ExecutionContext) = new ActionFilter[Request] {
    def executionContext = ec

    def filter[A](input: Request[A]): Future[Option[Result]] = authenticate(input)
  }

  private case class PayloadBasedApplicationTypeFilter(accessTypes: List[AccessType]) extends ApplicationTypeFilter(accessTypes, false) {

    final protected def deriveAccessType[A](request: Request[A]) =
      Future((Json.parse(request.body.toString) \ "access" \ "accessType").asOpt[AccessType])
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
}
