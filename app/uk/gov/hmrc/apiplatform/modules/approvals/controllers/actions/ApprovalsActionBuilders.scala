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

package uk.gov.hmrc.apiplatform.modules.approvals.controllers.actions


import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import scala.concurrent.ExecutionContext
import play.api.mvc._
import scala.concurrent.Future
import play.api.libs.json.{Json, JsObject}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationDataService
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.ExtendedSubmission
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper


trait JsonErrorResponse {
  self: BackendController =>

  def asBody(errorCode: String, message: Json.JsValueWrapper): JsObject =
    Json.obj(
      "code" -> errorCode.toString,
      "message" -> message
    )

  def applicationNotFound(applicationId: ApplicationId) = 
    NotFound(asBody("APPLICATION_NOT_FOUND", s"Application ${applicationId.value} doesn't exist"))

  def submissionNotFound(applicationId: ApplicationId) = 
    NotFound(asBody("SUBMISSION_NOT_FOUND", s"No submission found for application ${applicationId.value}"))
}


class ApplicationRequest[A](
    val application: ApplicationData,
    val request: Request[A]
) extends WrappedRequest[A](request)

class ApplicationSubmissionRequest[A](
    val extSubmission: ExtendedSubmission, 
    val applicationRequest: ApplicationRequest[A]
) extends ApplicationRequest[A](applicationRequest.application, applicationRequest.request) {
  lazy val submission = extSubmission.submission
}


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
        .map(extSubmission => new ApplicationSubmissionRequest[A](extSubmission, input))
        .value
      }
    }

  // def withApplication(applicationId: ApplicationId)(block: ApplicationRequest[AnyContent] => Future[Result])(implicit ec: ExecutionContext): Action[AnyContent] = {
  //   Action.async { implicit request =>
  //     (
  //       applicationRequestRefiner(applicationId)
  //     ).invokeBlock(request, block)
  //   }
  // }

  def withApplicationAndSubmission(applicationId: ApplicationId)(block: ApplicationSubmissionRequest[AnyContent] => Future[Result])(implicit ec: ExecutionContext): Action[AnyContent] = {
    Action.async { implicit request =>
      (
        applicationRequestRefiner(applicationId) andThen
        submissionRefiner(applicationId)
      ).invokeBlock(request, block)
    }  
  }

}