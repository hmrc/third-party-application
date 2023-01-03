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

import play.api.mvc._
import uk.gov.hmrc.thirdpartyapplication.config.AuthControlConfig
import uk.gov.hmrc.thirdpartyapplication.controllers.MaybeMatchesAuthorisationKeyRequest
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import java.util.Base64
import java.nio.charset.StandardCharsets
import cats.implicits._

trait AuthKeyRefiner {
  self: BaseController =>
  
  def authControlConfig: AuthControlConfig

  def authKeyRefiner(implicit ec: ExecutionContext): ActionRefiner[Request, MaybeMatchesAuthorisationKeyRequest] =
    new ActionRefiner[Request, MaybeMatchesAuthorisationKeyRequest] {

      override protected def executionContext: ExecutionContext = ec

      def refine[A](request: Request[A]): Future[Either[Result, MaybeMatchesAuthorisationKeyRequest[A]]] = {
        def matchesAuthorisationKey: Boolean = {
          def base64Decode(stringToDecode: String): Try[String] = Try(new String(Base64.getDecoder.decode(stringToDecode), StandardCharsets.UTF_8))

          request.headers.get(AUTHORIZATION) match {
            case Some(authHeader) => base64Decode(authHeader).map(_ == authControlConfig.authorisationKey).getOrElse(false)
            case _                => false
          }
        }

        val authKeyCheck = authControlConfig.enabled && request.headers.hasHeader(AUTHORIZATION) && matchesAuthorisationKey
        (MaybeMatchesAuthorisationKeyRequest[A](authKeyCheck, request)).asRight[Result].pure[Future]
      }
    }
}
