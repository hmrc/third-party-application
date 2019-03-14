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

package uk.gov.hmrc.thirdpartyapplication.controllers

import java.util.UUID

import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.auth.core.Enrolment
import uk.gov.hmrc.auth.core.retrieve.EmptyRetrieval
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.http.logging.Authorization
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.thirdpartyapplication.connector.{AuthConfig, AuthConnector}
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode.APPLICATION_NOT_FOUND
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.{AccessType, PRIVILEGED, ROPC, STANDARD}
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait AuthorisationWrapper {

  val authConnector: AuthConnector
  val applicationService: ApplicationService
  val authConfig: AuthConfig

  def requiresAuthentication(): ActionBuilder[Request] = {
    Action andThen AuthenticatedAction()
  }

  def requiresAuthenticationFor(accessTypes: AccessType*): ActionBuilder[Request] =
    Action andThen PayloadBasedApplicationTypeFilter(accessTypes)

  def requiresAuthenticationFor(uuid: UUID, accessTypes: AccessType*): ActionBuilder[Request] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, failOnAccessTypeMismatch = false, accessTypes)

  def requiresAuthenticationForStandardApplications(uuid: UUID): ActionBuilder[Request] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, failOnAccessTypeMismatch = true, Seq(STANDARD))

  def requiresAuthenticationForPrivilegedOrRopcApplications(uuid: UUID): ActionBuilder[Request] =
    Action andThen RepositoryBasedApplicationTypeFilter(uuid, failOnAccessTypeMismatch = false, Seq(PRIVILEGED, ROPC))

  private case class AuthenticatedAction() extends AuthenticationFilter() {
    def filter[A](input: Request[A]) = authenticate(input)
  }

  private abstract class AuthenticationFilter() extends ActionFilter[Request] {
    def authenticate[A](request: Request[A]): Future[Option[Result]] = {
      val hcc = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)
      implicit val hc = request.headers.get("X-Gatekeeper-Authorization") match {
        case Some(token) => hcc.copy(authorization = Some(Authorization(token)))
        case None => hcc
      }

      val hasAnyGatekeeperEnrolment = Enrolment(authConfig.userRole) or Enrolment(authConfig.superUserRole) or Enrolment(authConfig.adminRole)
      authConnector.authorise(hasAnyGatekeeperEnrolment, EmptyRetrieval).map { _ => None }
    }
  }

  private case class PayloadBasedApplicationTypeFilter(accessTypes: Seq[AccessType])
    extends ApplicationTypeFilter(false, accessTypes) {

    override protected def deriveAccessType[A](request: Request[A]) =
      Future((Json.parse(request.body.toString) \ "access" \ "accessType").asOpt[AccessType])
  }

  private case class RepositoryBasedApplicationTypeFilter(applicationId: UUID,
                                                          failOnAccessTypeMismatch: Boolean, accessTypes: Seq[AccessType])
    extends ApplicationTypeFilter(failOnAccessTypeMismatch, accessTypes) {

    override protected def deriveAccessType[A](request: Request[A]) =
      applicationService.fetch(applicationId).map {
        case Some(app) => Some(app.access.accessType)
        case None => throw new NotFoundException(s"application $applicationId doesn't exist")
      }
  }

  private abstract class ApplicationTypeFilter(failOnAccessTypeMismatch: Boolean = false,
                                               accessTypes: Seq[AccessType]) extends AuthenticationFilter() {

    override def filter[A](request: Request[A]) =
      deriveAccessType(request) flatMap {
        case Some(accessType) if accessTypes.contains(accessType) => authenticate(request)
        case Some(_) if failOnAccessTypeMismatch =>
          Future.successful(Some(Results.Forbidden(JsErrorResponse(APPLICATION_NOT_FOUND, "application access type mismatch"))))

        case _ => Future(None)
      } recover {
        case e: NotFoundException => Some(Results.NotFound(JsErrorResponse(APPLICATION_NOT_FOUND, e.getMessage)))
      }

    protected def deriveAccessType[A](request: Request[A]): Future[Option[AccessType]]
  }

}
