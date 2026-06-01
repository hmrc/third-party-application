/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers.query

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import cats.syntax.either._
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.ApplicationQuery.{GeneralOpenEndedApplicationQuery, PaginatedApplicationQuery}
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.{ApplicationQuery, SingleApplicationQuery}
import uk.gov.hmrc.thirdpartyapplication.controllers.common.{ExtraHeadersController, JsonUtils}
import uk.gov.hmrc.thirdpartyapplication.services.query.QueryService
import uk.gov.hmrc.thirdpartyapplication.util.MetricsTimer

@Singleton
class QueryController @Inject() (
    queryService: QueryService,
    cc: ControllerComponents,
    val metrics: Metrics
  )(implicit val ec: ExecutionContext
  ) extends ExtraHeadersController(cc)
    with JsonUtils
    with ApplicationLogger
    with MetricsTimer {

  private lazy val AcceptsStreamedJson = Accepting("application/stream+json")
  private lazy val AcceptsHmrcVndJson  = Accepting("application/hmrc.vnd.1.0+json")
  private lazy val AcceptsVndHmrcJson  = Accepting("application/vnd.hmrc.1.0+json")

  private def asBody(errorCode: String, message: Json.JsValueWrapper): JsObject =
    Json.obj(
      "code"    -> errorCode.toString,
      "message" -> message
    )

  private val applicationNotFound   = NotFound(asBody("APPLICATION_NOT_FOUND", "No application found for query"))
  private val streamingNotSupported = BadRequest(asBody("STREAMING_NOT_SUPPORTED", "Streaming is not supported for this query"))

  private def parseAndValidate(params: Map[String, Seq[String]], headers: Map[String, Seq[String]])(implicit hc: HeaderCarrier): Either[Result, ApplicationQuery] = {
    ParamsValidator.parseAndValidateParams(params, headers, hc.extraHeaders.toMap)
      .toEither
      .bimap(
        nel => BadRequest(asBody("INVALID_QUERY", nel.toList)),
        params => ApplicationQuery.attemptToConstructQuery(params)
      )
  }

  private def handleRequest(params: Map[String, Seq[String]], headers: Map[String, Seq[String]])(implicit request: Request[_]): Future[Result] = {
    parseAndValidate(params, headers)
      .fold(
        errResult => successful(errResult),
        validQuery =>
          render.async {
            case Accepts.Json()                              => execute(validQuery)(false)
            // These should never be in API Platform code... but ebrdige configurations cause both these to be possible.
            case AcceptsHmrcVndJson() | AcceptsVndHmrcJson() => execute(validQuery)(false)
            case AcceptsStreamedJson()                       => execute(validQuery)(true)
          }
      )
  }

  def queryDispatcher() = Action.async { implicit request =>
    handleRequest(request.queryString, request.headers.toMap)
  }

  def queryPost(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    withJsonBody[Map[String, Seq[String]]] { payload =>
      handleRequest(payload, request.headers.toMap)
    }
  }

  private def execute(appQry: ApplicationQuery)(streamed: Boolean)(implicit hc: HeaderCarrier): Future[Result] = {
    val appQryText = appQry.asLogText
    logger.info(s"Executing query: $appQryText")

    timeFuture(s"$appQryText", "QueryController.execute") {
      appQry match {
        case q: SingleApplicationQuery =>
          if (streamed) {
            successful(streamingNotSupported)
          } else {
            queryService.fetchSingleApplicationByQuery(q).map {
              _.fold(applicationNotFound)(app => Ok(Json.toJson(app)))
            }
          }

        case q: GeneralOpenEndedApplicationQuery =>
          if (streamed) {
            val wrappedSource: Source[ByteString, _] =
              queryService.fetchApplicationsByQueryStream(q).map(qas => ByteString(Json.toJson(qas).toString))
            successful(Ok.chunked(wrappedSource, Some("application/stream+json")))
          } else {
            queryService.fetchApplicationsByQuery(q).map(apps => Ok(Json.toJson(apps.toList)))
          }

        case q: PaginatedApplicationQuery =>
          if (streamed) {
            successful(streamingNotSupported)
          } else {
            queryService.fetchPaginatedApplications(q).map(results => Ok(Json.toJson(results)))
          }
      }
    }
  }
}
