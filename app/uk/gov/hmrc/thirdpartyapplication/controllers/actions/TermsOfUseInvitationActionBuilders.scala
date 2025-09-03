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

package uk.gov.hmrc.thirdpartyapplication.controllers.actions

import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.gkauth.services.{LdapGatekeeperRoleAuthorisationService, StrideGatekeeperRoleAuthorisationService}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.{ApplicationDataService, TermsOfUseInvitationService}

class ApplicationRequest[A](val application: StoredApplication, val request: Request[A]) extends WrappedRequest[A](request)

object TermsOfUseInvitationActionBuilders {

  object ApplicationStateFilter {
    type Type = State => Boolean

    val notProduction: Type = _ != State.PRODUCTION
    val production: Type    = _ == State.PRODUCTION
    val preProduction: Type = _ == State.PRE_PRODUCTION
    val inTesting: Type     = _ == State.TESTING
    val allAllowed: Type    = _ => true

    val pendingApproval: Type = s =>
      s == State.PENDING_GATEKEEPER_APPROVAL ||
        s == State.PENDING_REQUESTER_VERIFICATION ||
        s == State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
  }
}

trait TermsOfUseInvitationActionBuilders {
  self: BackendController =>

  import TermsOfUseInvitationActionBuilders.ApplicationStateFilter
  import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper

  def applicationDataService: ApplicationDataService
  def submissionsService: SubmissionsService
  def termsOfUseInvitationService: TermsOfUseInvitationService
  def ldapGatekeeperRoleAuthorisationService: LdapGatekeeperRoleAuthorisationService
  def strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService

  implicit def ec: ExecutionContext

  private val E = EitherTHelper.make[Result]

  private def anyAuthenticatedUserRefiner()(implicit ec: ExecutionContext): ActionRefiner[Request, Request] =
    new ActionRefiner[Request, Request] {
      def executionContext: ExecutionContext = ec

      override def refine[A](request: Request[A]): Future[Either[Result, Request[A]]] = {
        implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

        ldapGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole()
          .recoverWith { case NonFatal(_) => ldapGatekeeperRoleAuthorisationService.UNAUTHORIZED_RESPONSE }
          .flatMap(_ match {
            case Some(failureToAuthorise) =>
              strideGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole()
                .flatMap(_ match {
                  case None                     => successful(Right(request))
                  case Some(failureToAuthorise) => successful(Left(failureToAuthorise))
                })
            case None                     => successful(Right(request))
          })
      }
    }

  private def applicationRequestRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[Request, ApplicationRequest] =
    new ActionRefiner[Request, ApplicationRequest] {
      def executionContext: ExecutionContext = ec

      override def refine[A](request: Request[A]): Future[Either[Result, ApplicationRequest[A]]] = {
        E.fromOptionF(
          applicationDataService
            .fetchApp(applicationId),
          NotFound
        )
          .map(data => new ApplicationRequest[A](data, request))
          .value
      }
    }

  private def noSubmissionRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[ApplicationRequest, ApplicationRequest] =
    new ActionRefiner[ApplicationRequest, ApplicationRequest] {
      def executionContext = ec

      override def refine[A](input: ApplicationRequest[A]): Future[Either[Result, ApplicationRequest[A]]] = {
        submissionsService.fetchLatest(applicationId).map {
          case Some(value) => Left(Conflict)
          case None        => Right(input)
        }
      }
    }

  private def noInvitationRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[ApplicationRequest, ApplicationRequest] =
    new ActionRefiner[ApplicationRequest, ApplicationRequest] {
      def executionContext = ec

      override def refine[A](input: ApplicationRequest[A]): Future[Either[Result, ApplicationRequest[A]]] = {
        termsOfUseInvitationService.fetchInvitation(applicationId).map {
          case Some(value) => Left(Conflict)
          case None        => Right(input)
        }
      }
    }

  def anyAuthenticatedGatekeeperUserWithProductionApplicationAndNoSubmissionAndNoInvitation(
      allowedStateFilter: ApplicationStateFilter.Type = ApplicationStateFilter.production
    )(
      applicationId: ApplicationId
    )(
      block: ApplicationRequest[_] => Future[Result]
    ): Action[AnyContent] = {
    Action.async { implicit request =>
      (
        anyAuthenticatedUserRefiner() andThen
          applicationRequestRefiner(applicationId) andThen
          noSubmissionRefiner(applicationId) andThen
          noInvitationRefiner(applicationId)
      ).invokeBlock(request, block)
    }
  }
}
