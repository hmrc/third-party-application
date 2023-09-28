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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers.actions

import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.{Questionnaire, QuestionnaireProgress, Submission}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationDataService

trait JsonErrorResponse {
  self: BackendController =>

  def asBody(errorCode: String, message: Json.JsValueWrapper): JsObject =
    Json.obj(
      "code"    -> errorCode.toString,
      "message" -> message
    )

  def applicationNotFound(applicationId: ApplicationId) =
    NotFound(asBody("APPLICATION_NOT_FOUND", s"Application ${applicationId.value} doesn't exist"))

  def submissionNotFound(applicationId: ApplicationId) =
    NotFound(asBody("SUBMISSION_NOT_FOUND", s"No submission found for application ${applicationId.value}"))

  def applicationInIncorrectState(applicationId: ApplicationId, state: String) =
    PreconditionFailed(asBody("APPLICATION_IN_INCORRECT_STATE", s"Application is not in state #'${state}'"))

  def submissionInIncorrectState(applicationId: ApplicationId, state: String) =
    PreconditionFailed(asBody("SUBMISSION_IN_INCORRECT_STATE", s"Submission for $applicationId is not in state #'$state'"))
}

class ApplicationRequest[A](
    val application: ApplicationData,
    val request: Request[A]
  ) extends WrappedRequest[A](request)

class ApplicationSubmissionRequest[A](
    val submission: Submission,
    val applicationRequest: ApplicationRequest[A]
  ) extends ApplicationRequest[A](applicationRequest.application, applicationRequest.request)

class ApplicationExtendedSubmissionRequest[A](
    val submission: Submission,
    val questionnaireProgress: Map[Questionnaire.Id, QuestionnaireProgress],
    val applicationRequest: ApplicationRequest[A]
  ) extends ApplicationRequest[A](applicationRequest.application, applicationRequest.request)

trait ApprovalsActionBuilders extends JsonErrorResponse {
  self: BackendController =>

  import cats.implicits._

  def applicationDataService: ApplicationDataService
  def submissionService: SubmissionsService
  implicit def ec: ExecutionContext

  private val E = EitherTHelper.make[Result]

  def applicationRequestRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[Request, ApplicationRequest] =
    new ActionRefiner[Request, ApplicationRequest] {
      override protected def executionContext: ExecutionContext = ec

      override def refine[A](request: Request[A]): Future[Either[Result, ApplicationRequest[A]]] = {
        E.fromOptionF(applicationDataService.fetchApp(applicationId), applicationNotFound(applicationId))
          .map(ar => new ApplicationRequest[A](ar, request))
          .value
      }
    }

  private def submissionRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[ApplicationRequest, ApplicationSubmissionRequest] =
    new ActionRefiner[ApplicationRequest, ApplicationSubmissionRequest] {
      def executionContext = ec

      override def refine[A](input: ApplicationRequest[A]): Future[Either[Result, ApplicationSubmissionRequest[A]]] = {
        E.fromOptionF(submissionService.fetchLatest(applicationId), submissionNotFound(applicationId))
          .map(submission => new ApplicationSubmissionRequest[A](submission, input))
          .value
      }
    }

  private def extendedSubmissionRefiner(applicationId: ApplicationId)(implicit ec: ExecutionContext): ActionRefiner[ApplicationRequest, ApplicationExtendedSubmissionRequest] =
    new ActionRefiner[ApplicationRequest, ApplicationExtendedSubmissionRequest] {
      def executionContext = ec

      override def refine[A](input: ApplicationRequest[A]): Future[Either[Result, ApplicationExtendedSubmissionRequest[A]]] = {
        E.fromOptionF(submissionService.fetchLatestExtended(applicationId), submissionNotFound(applicationId))
          .map(extSubmission => new ApplicationExtendedSubmissionRequest[A](extSubmission.submission, extSubmission.questionnaireProgress, input))
          .value
      }
    }

  def withApplicationAndSubmission(
      applicationId: ApplicationId
    )(
      block: ApplicationSubmissionRequest[AnyContent] => Future[Result]
    )(implicit ec: ExecutionContext
    ): Action[AnyContent] = {
    Action.async { implicit request =>
      (
        applicationRequestRefiner(applicationId) andThen
          submissionRefiner(applicationId)
      ).invokeBlock(request, block)
    }
  }

  def withApplicationAndExtendedSubmission(
      applicationId: ApplicationId
    )(
      block: ApplicationExtendedSubmissionRequest[AnyContent] => Future[Result]
    )(implicit ec: ExecutionContext
    ): Action[AnyContent] = {
    Action.async { implicit request =>
      (
        applicationRequestRefiner(applicationId) andThen
          extendedSubmissionRefiner(applicationId)
      ).invokeBlock(request, block)
    }
  }

}
