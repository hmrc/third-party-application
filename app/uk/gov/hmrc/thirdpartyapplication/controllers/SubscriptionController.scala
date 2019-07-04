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

import java.util.UUID

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json.toJson
import play.api.mvc._
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository

import scala.concurrent.ExecutionContext

@Singleton
class SubscriptionController @Inject()(subscriptionRepository: SubscriptionRepository)
                                     (implicit val ec: ExecutionContext) extends CommonController {

  def getSubscribers(context: String, version: String): Action[AnyContent] = Action.async { implicit request =>
    subscriptionRepository.getSubscribers(APIIdentifier(context, version)).map(subscribers => Ok(toJson(SubscribersResponse(subscribers)))) recover recovery
  }

}

case class SubscribersResponse(subscribers: Set[UUID])
