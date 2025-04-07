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

package uk.gov.hmrc.apiplatform.modules.test_only.controllers

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import play.api.libs.json.Json
import play.api.mvc._
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.test_only.services.CloneApplicationService
import uk.gov.hmrc.thirdpartyapplication.controllers.JsonUtils
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService

@Singleton
class TestOnlyApplicationController @Inject() (
    applicationService: ApplicationService,
    cloneAppService: CloneApplicationService,
    mcc: MessagesControllerComponents
  )(implicit val ec: ExecutionContext
  ) extends BackendController(mcc) with JsonUtils {

  val noAuditing: StoredApplication => Future[AuditResult] = _ => successful(uk.gov.hmrc.play.audit.http.connector.AuditResult.Disabled)

  def deleteApplication(id: ApplicationId): Action[AnyContent] = Action.async { implicit request =>
    val ET = EitherTHelper.make[Result]

    (
      for {
        app <- ET.fromOptionF(applicationService.fetch(id).value, NotFound("No application was found"))
        _   <- ET.liftF(applicationService.deleteApplication(id, None, noAuditing))
      } yield NoContent
    )
      .fold(identity, identity)
  }

  def cloneApplication(appId: ApplicationId): Action[AnyContent] = Action.async { implicit request =>
    cloneAppService.cloneApplication(appId).flatMap(_ match {
      case Left(newAppId) => applicationService.deleteApplication(newAppId, None, noAuditing).map(_ => InternalServerError)
      case Right(newApp)  => successful(Created(Json.toJson(StoredApplication.asApplication(newApp))))
    })
  }
}
