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

import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.HeaderCarrierConverter
import uk.gov.hmrc.thirdpartyapplication.controllers.ErrorCode.FORBIDDEN
import uk.gov.hmrc.thirdpartyapplication.models.AccessType
import uk.gov.hmrc.thirdpartyapplication.models.AccessType.AccessType
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._

import scala.concurrent.Future

trait RequestValidationWrapper {

  def requiresRequestValidation(): ActionBuilder[Request] = {
    Action andThen RequestValidationFilter()
  }

  private case class RequestValidationFilter() extends ActionFilter[Request] {
    override protected def filter[A](request: Request[A]): Future[Option[Result]] = {
      implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromHeadersAndSession(request.headers, None)

      def validAccessTypes = List(AccessType.PRIVILEGED, AccessType.ROPC)

      def requestedAccessType = (Json.parse(request.body.toString) \ "access" \ "accessType").asOpt[AccessType]

      requestedAccessType match {
        case Some(accessType) if validAccessTypes.contains(accessType) => Future.successful(None)
        case Some(_) => Future.successful(Some(Results.Forbidden(JsErrorResponse(FORBIDDEN, "application access type mismatch"))))
        case None => Future.successful(None)
      }
    }
  }

}
