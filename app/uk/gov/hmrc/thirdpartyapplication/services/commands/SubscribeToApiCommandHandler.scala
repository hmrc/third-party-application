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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import cats._
import cats.data._
import cats.implicits._

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.SubscribeToApi
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.{ApplicationEvent, EventId}
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationService
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType

@Singleton
class SubscribeToApiCommandHandler @Inject() (
    subscriptionRepository: SubscriptionRepository,
    strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler {

  import CommandHandler._

  private def validate(app: ApplicationData, cmd: SubscribeToApi, rolePassed: Boolean, alreadySubcribed: Boolean): Validated[Failures, Unit] = {
    def isAuthorisedUser       = cond(rolePassed, s"Unauthorized to subscribe any API to app ${app.name}")
    def notAlreadySubscribedTo = cond(!alreadySubcribed, s"Application ${app.name} is already subscribed to API ${cmd.apiIdentifier.asText(" v")}")

    Apply[Validated[Failures, *]].map2(
      isAuthorisedUser,
      notAlreadySubscribedTo
    ) { case _ => () }
  }

  private def asEvents(app: ApplicationData, cmd: SubscribeToApi): NonEmptyList[ApplicationEvent] = {
    NonEmptyList.of(
      ApiSubscribedV2(
        id = EventId.random,
        applicationId = app.id,
        eventDateTime = cmd.timestamp.instant,
        actor = cmd.actor,
        context = cmd.apiIdentifier.context,
        version = cmd.apiIdentifier.versionNbr
      )
    )
  }

  private def performRoleCheckAsRequired(app: ApplicationData)(implicit hc: HeaderCarrier) = {
    if (List(AccessType.PRIVILEGED, AccessType.ROPC).contains(app.access.accessType))
      strideGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole().map(_.isEmpty)
    else
      Future.successful(true)
  }

  def process(app: ApplicationData, cmd: SubscribeToApi)(implicit hc: HeaderCarrier): AppCmdResultT = {
    for {
      rolePassed       <- E.liftF(performRoleCheckAsRequired(app))
      alreadySubcribed <- E.liftF(subscriptionRepository.isSubscribed(app.id, cmd.apiIdentifier))
      valid            <- E.fromEither(validate(app, cmd, rolePassed, alreadySubcribed).toEither)
      _                <- E.liftF(subscriptionRepository.add(app.id, cmd.apiIdentifier))
    } yield (app, asEvents(app, cmd))
  }
}
