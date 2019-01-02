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

package uk.gov.hmrc.controllers

import java.util.UUID

import play.api.libs.json.Json
import play.api.mvc.Results._
import play.api.mvc.{Action, Request, _}
import uk.gov.hmrc.connector.AuthConnector
import uk.gov.hmrc.controllers.ErrorCode._
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.models.AccessType.{AccessType, PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.models.AuthRole
import uk.gov.hmrc.models.AuthRole.APIGatekeeper
import uk.gov.hmrc.models.JsonFormatters._
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.services.ApplicationService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait AuthorisationWrapper {

  val authConnector: AuthConnector
  val applicationService: ApplicationService

  def requiresRole(requiredRole: AuthRole): ActionBuilder[Request] = {
    Action andThen AuthenticatedAction(requiredRole)
  }

  def requiresRoleFor(authRole: AuthRole, accessTypes: AccessType*): ActionBuilder[Request] =
    Action andThen PayloadBasedApplicationTypeFilter(authRole, accessTypes)

  def requiresRoleFor(uuid: UUID, authRole: AuthRole, accessTypes: AccessType*): ActionBuilder[Request] =
    Action andThen RepositoryBasedApplicationTypeFilter(authRole, uuid, failOnAccessTypeMismatch = false, accessTypes)

  def requiresGatekeeperForStandardApplications(uuid: UUID): ActionBuilder[Request] =
    Action andThen RepositoryBasedApplicationTypeFilter(APIGatekeeper, uuid, failOnAccessTypeMismatch = true, Seq(STANDARD))

  def requiresGatekeeperForPrivilegedOrRopcApplications(uuid: UUID): ActionBuilder[Request] =
    Action andThen RepositoryBasedApplicationTypeFilter(APIGatekeeper, uuid, failOnAccessTypeMismatch = false, Seq(PRIVILEGED, ROPC))

  private case class AuthenticatedAction(requiredRole: AuthRole) extends AuthenticationFilter(requiredRole) {
    def filter[A](input: Request[A]) = authenticate(input)
  }

  private abstract class AuthenticationFilter(authRole: AuthRole) extends ActionFilter[Request] {
    def authenticate[A](request: Request[A]) = {
      val hc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      authConnector.authorized(authRole)(hc).map {
        case true => None
        case false =>
          Some(Unauthorized(JsErrorResponse(UNAUTHORIZED,
            s"Action requires authority: '${authRole.scope}:${authRole.name}'")))
      }
    }
  }

  private case class PayloadBasedApplicationTypeFilter(requiredAuthRole: AuthRole, accessTypes: Seq[AccessType])
    extends ApplicationTypeFilter(requiredAuthRole, false, accessTypes) {

    override protected def deriveAccessType[A](request: Request[A]) =
      Future((Json.parse(request.body.toString) \ "access" \ "accessType").asOpt[AccessType])
  }

  private case class RepositoryBasedApplicationTypeFilter(requiredAuthRole: AuthRole, applicationId: UUID,
                                                          failOnAccessTypeMismatch: Boolean, accessTypes: Seq[AccessType])
    extends ApplicationTypeFilter(requiredAuthRole, failOnAccessTypeMismatch, accessTypes) {

    override protected def deriveAccessType[A](request: Request[A]) =
      applicationService.fetch(applicationId).map {
        case Some(app) => Some(app.access.accessType)
        case None => throw new NotFoundException(s"application $applicationId doesn't exist")
      }
  }

  private abstract class ApplicationTypeFilter(authRole: AuthRole, failOnAccessTypeMismatch: Boolean = false,
                                               accessTypes: Seq[AccessType]) extends AuthenticationFilter(authRole) {

    override def filter[A](request: Request[A]) =
      deriveAccessType(request) flatMap {
        case Some(accessType) if accessTypes.contains(accessType) => authenticate(request)
        case Some(_) if failOnAccessTypeMismatch =>
          Future.successful(Some(Results.Unauthorized(JsErrorResponse(APPLICATION_NOT_FOUND, "application access type mismatch"))))

        case _ => Future(None)
      } recover {
        case e: NotFoundException => Some(Results.NotFound(JsErrorResponse(APPLICATION_NOT_FOUND, e.getMessage)))
      }

    protected def deriveAccessType[A](request: Request[A]): Future[Option[AccessType]]
  }
}
