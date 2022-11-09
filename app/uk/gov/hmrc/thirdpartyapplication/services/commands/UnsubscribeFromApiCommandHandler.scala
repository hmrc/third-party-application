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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import cats.data.{NonEmptyList, Validated}
import play.api.mvc.Result
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.{PRIVILEGED, ROPC}
import uk.gov.hmrc.thirdpartyapplication.domain.models.{UnsubscribeFromApi, UpdateApplicationEvent}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UnsubscribeFromApiCommandHandler @Inject() (
    strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import UpdateApplicationEvent._

  private def asEvents(app: ApplicationData, cmd: UnsubscribeFromApi): NonEmptyList[UpdateApplicationEvent] = {
    NonEmptyList.of(
      ApiUnsubscribed(
        id = UpdateApplicationEvent.Id.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp,
        actor = cmd.actor,
        context = cmd.apiIdentifier.context.value,
        version = cmd.apiIdentifier.version.value
      )
    )
  }

  def process(app: ApplicationData, cmd: UnsubscribeFromApi)(implicit hc: HeaderCarrier): CommandHandler.Result = {
    if (List(PRIVILEGED, ROPC).contains(app.access.accessType)) {
      strideGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole().map {
        case None            => Validated.valid(asEvents(app, cmd))
        case Some(_: Result) => Validated.invalidNec(s"Unauthorized to unsubscribe any API from app ${app.name}")
      }
    } else {
      Future.successful(Validated.valid(asEvents(app, cmd)))
    }
  }
}
