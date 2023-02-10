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

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.data.NonEmptyChain

import play.api.libs.json.Json
import play.api.mvc._

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationCommand, ApplicationCommandFormatters, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.ApplicationId

object ApplicationCommandController {
  case class DispatchResult(applicationResponse: ApplicationResponse, events: List[UpdateApplicationEvent])

  object DispatchResult {
    implicit val format = Json.format[DispatchResult]
  }
}

@Singleton
class ApplicationCommandController @Inject() (
    val applicationCommandDispatcher: ApplicationCommandDispatcher,
    val applicationService: ApplicationService,
    cc: ControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with JsonUtils
    with ApplicationCommandFormatters
    with ApplicationLogger {

  import cats.implicits._
  import ApplicationCommandController._
  import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler.CommandSuccess

  private def fails(applicationId: ApplicationId)(e: NonEmptyChain[String]) = {
    logger.warn(s"Command Process failed for $applicationId because ${e.toList.mkString("[", ",", "]")}")
    BadRequest("Failed to process command")
  }

  def update(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    def passes(result: CommandSuccess) = {
      Ok(Json.toJson(ApplicationResponse(data = result._1)))
    }

    withJsonBody[ApplicationCommand] { command =>
      applicationCommandDispatcher.dispatch(applicationId, command).fold(fails(applicationId), passes(_))
    }
  }

  def dispatch(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    def passes(result: CommandSuccess) = {
      val output = DispatchResult(ApplicationResponse(data = result._1), result._2.toList)
      Ok(Json.toJson(output))
    }

    withJsonBody[ApplicationCommand] { command =>
      applicationCommandDispatcher.dispatch(applicationId, command).fold(fails(applicationId), passes(_))
    }
  }

}
