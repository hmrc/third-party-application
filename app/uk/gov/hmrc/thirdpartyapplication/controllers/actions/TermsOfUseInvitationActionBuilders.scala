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

import uk.gov.hmrc.thirdpartyapplication.controllers.TermsOfUseInvitationController
import play.api.mvc.Action
import play.api.mvc.AnyContent
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import scala.concurrent.ExecutionContext
import play.api.mvc.ActionRefiner
import scala.concurrent.Future
import play.api.mvc.Request
import play.api.mvc.Result
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import play.api.mvc.WrappedRequest
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationDataService
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.services.TermsOfUseInvitationService

class ApplicationRequest[A](val applicationId: ApplicationId, val request: Request[A]) extends WrappedRequest[A](request)

object TermsOfUseInvitationActionBuilders {
  object ApplicationStateFilter {
    type Type = State => Boolean
    
    val notProduction: Type   = _ != PRODUCTION
    val production: Type      = _ == PRODUCTION
    val preProduction: Type   = _ == PRE_PRODUCTION
    val inTesting: Type       = _ == TESTING
    val allAllowed: Type      = _ => true
    val pendingApproval: Type = s => 
      s == PENDING_GATEKEEPER_APPROVAL ||
      s == PENDING_REQUESTER_VERIFICATION ||
      s == PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION
  }
}

trait TermsOfUseInvitationActionBuilders {
  self: TermsOfUseInvitationController =>

  import TermsOfUseInvitationActionBuilders.ApplicationStateFilter
  import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper

  def applicationDataService: ApplicationDataService
  def submissionsService: SubmissionsService
  def termsOfUseInvitationService: TermsOfUseInvitationService
  
  private val E = EitherTHelper.make[Result]

  private def applicationRequestRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[Request, ApplicationRequest] =
    new ActionRefiner[Request, ApplicationRequest] {
      def executionContext: ExecutionContext = ec

      override def refine[A](request: Request[A]): Future[Either[Result, ApplicationRequest[A]]] = {
        import cats.implicits._

        E.fromOptionF(
          applicationDataService
          .fetchApp(applicationId),
          NotFound
        )
        .map(data => new ApplicationRequest[A](data.id, request))
        .value
      }
    }

  private def noSubmissionRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[ApplicationRequest, ApplicationRequest] =
    new ActionRefiner[ApplicationRequest, ApplicationRequest] {
      def executionContext = ec

      override def refine[A](input: ApplicationRequest[A]): Future[Either[Result, ApplicationRequest[A]]] = {
        submissionsService.fetchLatest(applicationId).map {
          case Some(value) => Left(Conflict)
          case None => Right(input)
        }
      }
    }

  private def noInvitationRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[ApplicationRequest, ApplicationRequest] = 
    new ActionRefiner[ApplicationRequest, ApplicationRequest] {
      def executionContext = ec

      override def refine[A](input: ApplicationRequest[A]): Future[Either[Result, ApplicationRequest[A]]] = {
        termsOfUseInvitationService.fetchInvitation(applicationId).map {
          case Some(value) => Left(Conflict)
          case None => Right(input)
        }
      }
    }

  def withProductionApplicationAdminUserAndNoSubmission(
    allowedStateFilter: ApplicationStateFilter.Type = ApplicationStateFilter.production
  )(
    applicationId: ApplicationId
  )(
    block: ApplicationRequest[AnyContent] => Future[Result]
  ): Action[AnyContent] = {
    Action.async { implicit request =>
      (
        applicationRequestRefiner(applicationId) andThen
          noSubmissionRefiner(applicationId) andThen
          noInvitationRefiner(applicationId)
      ).invokeBlock(request, block)
    }
  }
}
