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

import cats.data.NonEmptyChain
import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationUpdate, ApplicationUpdateFormatters, ApplicationId}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._

@Singleton
class ApplicationUpdateController @Inject()(val applicationUpdateService: ApplicationUpdateService,
                                            val applicationService: ApplicationService,
                                            val authConnector: AuthConnector,
                                            val authConfig: AuthConnector.Config,
                                            cc: ControllerComponents)(implicit val ec: ExecutionContext)
    extends ExtraHeadersController(cc)
    with JsonUtils
    with ApplicationUpdateFormatters
    with AuthorisationWrapper
    with ApplicationLogger {

  import cats.implicits._

  def update(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    def fails(e: NonEmptyChain[String]) = {
      logger.warn(s"Command Process failed because ${e.toList.mkString("[",",","]")}")
      BadRequest("Failed to process command")
    }

    def passes(applicationData: ApplicationData) = {
      Ok(Json.toJson(ApplicationResponse(data = applicationData)))
    }

    withJsonBody[ApplicationUpdate] { applicationUpdate =>
      applicationUpdateService.update(applicationId, applicationUpdate).fold(fails(_), passes(_))
    }
  }

}
