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
import scala.concurrent.ExecutionContext

import cats._
import cats.implicits._
import cats.data._

import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationService
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.{PRIVILEGED, ROPC}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.apiplatform.modules.gkauth.services.StrideGatekeeperRoleAuthorisationService
import uk.gov.hmrc.http.HeaderCarrier
import scala.concurrent.Future


@Singleton
class UnsubscribeFromApiCommandHandler @Inject() (
    subscriptionRepository: SubscriptionRepository,
    strideGatekeeperRoleAuthorisationService: StrideGatekeeperRoleAuthorisationService
)(implicit val ec: ExecutionContext) extends CommandHandler2 {

  import CommandHandler2._
  import UpdateApplicationEvent._

  private def validate(app: ApplicationData, cmd: UnsubscribeFromApi, rolePassed: Boolean, alreadySubcribed: Boolean): ValidatedNec[String, Unit] = {
    def isGatekeeperUser = cond(rolePassed, s"Unauthorized to unsubscribe any API from app ${app.name}")
    def alreadySubscribedTo = cond(alreadySubcribed, s"Application ${app.name} is not subscribed to API ${cmd.apiIdentifier.asText(" v")}")

    Apply[ValidatedNec[String, *]].map2(
      isGatekeeperUser,
      alreadySubscribedTo
    ) { case _ => () }
  }

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

  private def performRoleCheckAsRequired(app: ApplicationData)(implicit hc: HeaderCarrier) = {
    if(List(PRIVILEGED, ROPC).contains(app.access.accessType))
      strideGatekeeperRoleAuthorisationService.ensureHasGatekeeperRole().map(_.isEmpty)
    else
      Future.successful(true)
  }

  def process(app: ApplicationData, cmd: UnsubscribeFromApi)(implicit hc: HeaderCarrier): ResultT = {
    for {
      rolePassed <- E.liftF(performRoleCheckAsRequired(app))
      alreadySubcribed <- E.liftF(subscriptionRepository.isSubscribed(app.id, cmd.apiIdentifier))
      valid <- E.fromEither(validate(app, cmd, rolePassed, alreadySubcribed).toEither)
      _ <- E.liftF(subscriptionRepository.remove(app.id, cmd.apiIdentifier))
    } yield (app, asEvents(app, cmd))
  }
}
