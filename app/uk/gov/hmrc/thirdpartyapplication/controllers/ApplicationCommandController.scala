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

import play.api.libs.json.{Json, Reads}
import play.api.mvc._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.{ApplicationCommand, CommandFailures}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

object ApplicationCommandController {
  case class DispatchRequest(command: ApplicationCommand, verifiedCollaboratorsToNotify: Set[LaxEmailAddress])

  object DispatchRequest {

    val readsDispatchRequest: Reads[DispatchRequest]          = Json.reads[DispatchRequest]
    val readsCommandAsDispatchRequest: Reads[DispatchRequest] = ApplicationCommand.formatter.map(cmd => DispatchRequest(cmd, Set.empty))

    implicit val readsEitherAsDispatchRequest: Reads[DispatchRequest] = readsDispatchRequest orElse readsCommandAsDispatchRequest
  }

  case class DispatchResult(applicationResponse: ApplicationResponse, events: List[ApplicationEvent])

  object DispatchResult {
    import uk.gov.hmrc.apiplatform.modules.events.applications.domain.services.EventsInterServiceCallJsonFormatters._

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
    with ApplicationLogger {

  import cats.implicits._
  import ApplicationCommandController._

  private def fails(applicationId: ApplicationId)(e: CommandHandler.Failures) = {

    val details = e.toList.map(CommandFailures.describe)

    logger.warn(s"Command Process failed for $applicationId because ${details.mkString("[", ",", "]")}")
    BadRequest(Json.toJson(e.toList))
  }

  def update(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    def passes(result: CommandHandler.Success) = {
      Ok(Json.toJson(ApplicationResponse(data = result._1)))
    }

    withJsonBody[ApplicationCommand] { command =>
      applicationCommandDispatcher.dispatch(applicationId, command, Set.empty) // Eventually we want to migrate everything to use the /dispatch endpoint with email list
        .fold(fails(applicationId), passes(_))
    }
  }

  def dispatch(applicationId: ApplicationId) = Action.async(parse.json) { implicit request =>
    def passes(result: CommandHandler.Success) = {
      val output = DispatchResult(ApplicationResponse(data = result._1), result._2.toList)
      Ok(Json.toJson(output))
    }

    withJsonBody[DispatchRequest] { dispatchRequest =>
      applicationCommandDispatcher.dispatch(applicationId, dispatchRequest.command, dispatchRequest.verifiedCollaboratorsToNotify).fold(fails(applicationId), passes(_))
    }
  }
}
