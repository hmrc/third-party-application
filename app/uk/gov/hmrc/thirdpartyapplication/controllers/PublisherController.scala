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

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.UnsubscribeFromApi
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationCommandDispatcher

@Singleton
class PublisherController @Inject() (
    subscriptionRepository: SubscriptionRepository,
    applicationRepository: ApplicationRepository,
    applicationCommandDispatcher: ApplicationCommandDispatcher,
    cc: ControllerComponents,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends BackendController(cc) with JsonUtils with ClockNow {

  // What do we want to do if unsubscribing goes wrong??

  def deleteSubscribers(context: ApiContext, version: ApiVersionNbr): Action[AnyContent] = Action.async { implicit request =>
    val ts: Instant   = instant()
    val apiIdentifier = ApiIdentifier(context, version)
    val command       = UnsubscribeFromApi(Actors.Process("Publisher"), apiIdentifier, ts)

    def deleteSubscriptionFor(applicationId: ApplicationId)(implicit hc: HeaderCarrier): Future[Unit] = {
      applicationCommandDispatcher.dispatch(applicationId, command, Set.empty).fold(_ => (), _ => ())
    }

    (for {
      subscribers <- subscriptionRepository.getSubscribers(ApiIdentifier(context, version))
      xs          <- Future.sequence(subscribers.toList.map(id => deleteSubscriptionFor(id)))
    } yield xs)
      .map(_ => Ok)
  }
}
