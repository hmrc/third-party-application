/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services.commands.submission

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.Apply
import cats.data.{NonEmptyList, Validated}

import uk.gov.hmrc.apiplatform.modules.common.services.ClockNow
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.{Access, SellResellOrDistribute}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands.ChangeApplicationSellResellOrDistribute
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands.CommandHandler

@Singleton
class ChangeApplicationSellResellOrDistributeCommandHandler @Inject() (
    val applicationRepository: ApplicationRepository,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends CommandHandler with ClockNow {

  import CommandHandler._

  private def validate(app: StoredApplication): Validated[Failures, StoredApplication] = {
    Apply[Validated[Failures, *]].map(
      ensureStandardAccess(app)
    ) { case _ => app }
  }

  private def asEvents(
      app: StoredApplication,
      cmd: ChangeApplicationSellResellOrDistribute,
      oldSellResellOrDistribute: Option[SellResellOrDistribute],
      newSellResellOrDistribute: SellResellOrDistribute
    ): NonEmptyList[ApplicationEvent] = {

    NonEmptyList.of(ApplicationSellResellOrDistributeChanged(
      id = EventId.random,
      applicationId = app.id,
      eventDateTime = cmd.timestamp,
      actor = cmd.actor,
      oldSellResellOrDistribute,
      newSellResellOrDistribute
    ))

  }

  def process(app: StoredApplication, cmd: ChangeApplicationSellResellOrDistribute): AppCmdResultT = {

    def updateWithSellResellOrDistribute(applicationData: StoredApplication, newSellResellOrDistribute: SellResellOrDistribute): StoredApplication =
      applicationData.withAccess(getStandardAccess(applicationData).copy(sellResellOrDistribute = Some(newSellResellOrDistribute)))

    for {
      validateResult           <- E.fromValidated(validate(app))
      oldSellResellOrDistribute = getSellResellOrDistribute(app)
      newSellResellOrDistribute = cmd.sellResellOrDistribute
      persistedApplicationData <- E.liftF(applicationRepository.save(updateWithSellResellOrDistribute(app, newSellResellOrDistribute)))
      events                    = asEvents(app, cmd, oldSellResellOrDistribute, newSellResellOrDistribute)
    } yield (app, events)
  }

  private def getSellResellOrDistribute(applicationData: StoredApplication): Option[SellResellOrDistribute] =
    getStandardAccess(applicationData).sellResellOrDistribute

  private def getStandardAccess(applicationData: StoredApplication): Access.Standard =
    applicationData.access.asInstanceOf[Access.Standard]
}
