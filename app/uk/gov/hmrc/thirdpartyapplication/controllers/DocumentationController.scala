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

import controllers.AssetsBuilder
import javax.inject.Inject
import play.api.http.HttpErrorHandler
import play.api.mvc.Action
import uk.gov.hmrc.thirdpartyapplication.config.AppContext
import uk.gov.hmrc.thirdpartyapplication.models.APIAccess
import uk.gov.hmrc.play.microservice.controller.BaseController
import uk.gov.hmrc.thirdpartyapplication.views.txt

class DocumentationController @Inject()(httpErrorHandler: HttpErrorHandler, appContext: AppContext)
  extends AssetsBuilder(httpErrorHandler) with BaseController {

  def definition = Action {
    if(appContext.publishApiDefinition) {
      Ok(txt.definition(appContext.apiContext, APIAccess.build(appContext.access))).withHeaders(CONTENT_TYPE -> JSON)
    } else {
      NotFound
    }
  }

  def raml(version: String, file: String) = Action {
    if(appContext.publishApiDefinition) {
      Ok(txt.application(appContext.apiContext))
    } else {
      NotFound
    }
  }
}
