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

import akka.http.scaladsl.model.HttpHeader.ParsingResult.Ok
import cats.data.EitherT
import cats.implicits.catsStdInstancesForFuture
import play.api.libs.json._
import play.api.mvc.Results.{BadRequest, Conflict, NoContent}
import play.api.mvc._
import uk.gov.hmrc.apiplatform.modules.approvals.controllers.ResponsibleIndividualVerificationController.ErrorMessage
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.controllers.ApplicationUpdateController.{ApplicationUpdate, ApplicationUpdateRequest, UpdateName}
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationNameValidationResult, DuplicateName, InvalidName}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData


object ApplicationUpdateController {
  case class ApplicationUpdateRequest(requestType: String, parameters: Map[String,String])
  implicit val formatApplicationUpdateRequest = Json.format[ApplicationUpdateRequest]

  type ApplicationUpdateResult[A,B] = EitherT[Future,A,B]

  sealed trait ApplicationUpdate[A,B] {
    def update(applicationId: ApplicationId, applicationUpdateService: ApplicationUpdateService): ApplicationUpdateResult[A,B]
    def mapSuccess(successValue: B): Result
    def mapFailure(failureValue: A): Result
  }

  case class UpdateName(newName: String) extends ApplicationUpdate[ApplicationNameValidationResult, ApplicationData] {
    def update(applicationId: ApplicationId, applicationUpdateService: ApplicationUpdateService) =
      applicationUpdateService.updateApplicationName(applicationId, newName)

    override def mapSuccess(successValue: ApplicationData): Result = NoContent

    override def mapFailure(failureValue: ApplicationNameValidationResult): Result = failureValue match {
      case InvalidName => BadRequest(Json.toJson(ErrorMessage("Invalid name")))
      case DuplicateName => Conflict(Json.toJson(ErrorMessage("Duplicate name")))
    }
  }
}

@Singleton
class ApplicationUpdateController @Inject()(val applicationUpdateService: ApplicationUpdateService,
                                            val applicationService: ApplicationService,
                                            val authConnector: AuthConnector,
                                            val authConfig: AuthConnector.Config,
                                            cc: ControllerComponents)(implicit val ec: ExecutionContext)
    extends ExtraHeadersController(cc)
    with JsonUtils
    with AuthorisationWrapper
    with ApplicationLogger {

  //TODO is requiresAuthentication() ok for a generic endpoint?
  def update(applicationId: ApplicationId) = requiresAuthentication().async(parse.json) { implicit request =>
    withJsonBody[ApplicationUpdateRequest] { applicationUpdateRequest =>
      getUpdateOperation(applicationUpdateRequest) match {
        case Some(applicationUpdate) => applicationUpdate.update(applicationId, applicationUpdateService)
          .fold(applicationUpdate.mapFailure, applicationUpdate.mapSuccess)
        case None => Future.successful(BadRequest(s"Unknown operation: ${applicationUpdateRequest.requestType}"))
      }
    }
  }

  private def getUpdateOperation(applicationUpdateRequest: ApplicationUpdateRequest): Option[ApplicationUpdate[Any,Any]] = {
    (applicationUpdateRequest.requestType match {
      case "updateName" => Some(UpdateName(applicationUpdateRequest.parameters("newName")))
      case _ => None
    }).map(_.asInstanceOf[ApplicationUpdate[Any,Any]])
  }
}


