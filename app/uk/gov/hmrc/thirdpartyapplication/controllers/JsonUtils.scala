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

package uk.gov.hmrc.thirdpartyapplication.controllers

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import play.api.libs.json.{JsError, JsSuccess, JsValue, Reads}
import play.api.mvc.{AnyContent, Request, Result, Results}
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode._
import uk.gov.hmrc.thirdpartyapplication.models.{InvalidIpAllowlistException, ScopeNotFoundException}

// TODO : Sort these helper methods with plans to remove them - APIS-4766
trait JsonUtils extends Results with ApplicationLogger {
  self: BackendController =>

  override def withJsonBody[T](f: T => Future[Result])(implicit request: Request[JsValue], m: Manifest[T], reads: Reads[T]): Future[Result] = {
    withJson(request.body)(f)
  }

  def withJsonBodyFromAnyContent[T](f: T => Future[Result])(implicit request: Request[AnyContent], reads: Reads[T], d: DummyImplicit): Future[Result] = {
    request.body.asJson match {
      case Some(json) => withJson(json)(f)
      case _          => Future.successful(UnprocessableEntity(JsErrorResponse(INVALID_REQUEST_PAYLOAD, "Invalid Payload")))
    }
  }

  private def withJson[T](json: JsValue)(f: T => Future[Result])(implicit reads: Reads[T]): Future[Result] = {
    Try(json.validate[T]) match {
      case Success(JsSuccess(payload, _)) => f(payload)
      case Success(JsError(errs))         => Future.successful(UnprocessableEntity(JsErrorResponse(INVALID_REQUEST_PAYLOAD, JsError.toJson(errs))))
      case Failure(e)                     => Future.successful(UnprocessableEntity(JsErrorResponse(INVALID_REQUEST_PAYLOAD, e.getMessage)))
    }
  }

  def recovery: PartialFunction[Throwable, Result] = {
    case e: NotFoundException           => handleNotFound(e.getMessage)
    case e: ScopeNotFoundException      => NotFound(JsErrorResponse(SCOPE_NOT_FOUND, e.getMessage))
    case e: InvalidIpAllowlistException => BadRequest(JsErrorResponse(INVALID_IP_ALLOWLIST, e.getMessage))
    case e: Throwable                   =>
      logger.error(s"Error occurred: ${e.getMessage}", e)
      handleException(e)
  }

  private[controllers] def handleNotFound(message: String): Result = {
    NotFound(JsErrorResponse(APPLICATION_NOT_FOUND, message))
  }

  private[controllers] def handleException(e: Throwable) = {
    logger.error(s"An unexpected error occurred: ${e.getMessage}", e)
    InternalServerError(JsErrorResponse(UNKNOWN_ERROR, "An unexpected error occurred"))
  }

}
