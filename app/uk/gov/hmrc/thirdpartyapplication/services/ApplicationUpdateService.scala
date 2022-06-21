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

package uk.gov.hmrc.thirdpartyapplication.services

import cats.data.{EitherT, NonEmptyChain, NonEmptyList, Validated}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.services.events._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class ApplicationUpdateService @Inject()(
  applicationRepository: ApplicationRepository,
  changeProductionApplicationNameCmdHdlr: ChangeProductionApplicationNameCommandHandler,
  nameChangedNotificationEventHdlr: NameChangedNotificationEventHandler
) (implicit val ec: ExecutionContext) extends ApplicationLogger {
  import cats.implicits._
  private val E = EitherTHelper.make[NonEmptyChain[String]]

  def update(applicationId: ApplicationId, applicationUpdate: ApplicationUpdate)(implicit hc: HeaderCarrier): EitherT[Future, NonEmptyChain[String], ApplicationData] = {
    for {
      app              <- E.fromOptionF(applicationRepository.fetch(applicationId), NonEmptyChain(s"No application found with id $applicationId"))
      events           <- EitherT(processUpdate(app, applicationUpdate).map(_.toEither))
      repositoryEvents <- E.fromOption(NonEmptyList.fromList(events.collect{case e: UpdateApplicationRepositoryEvent => e}), NonEmptyChain(s"No repository events found for this command"))
      savedApp         <- E.liftF(applicationRepository.applyEvents(repositoryEvents))
      _                <- E.liftF(sendNotifications(events.collect{case e: UpdateApplicationNotificationEvent => e}))
    } yield savedApp
  }

  private def processUpdate(app: ApplicationData, applicationUpdate: ApplicationUpdate): CommandHandler.Result = {
    applicationUpdate match {
      case cmd: ChangeProductionApplicationName => changeProductionApplicationNameCmdHdlr.process(app, cmd)
      case _ => Future.successful(Validated.invalidNec(s"Unknown ApplicationUpdate type $applicationUpdate"))
    }
  }

  private def sendNotifications(events: List[UpdateApplicationNotificationEvent])(implicit hc: HeaderCarrier): Future[List[HasSucceeded]] = {
    def sendNotification(event: UpdateApplicationEvent) = {
      event match {
        case evt: UpdateApplicationEvent.NameChangedEmailSent => nameChangedNotificationEventHdlr.sendAdviceEmail(evt)
        case _  => throw new RuntimeException(s"UnexpectedEvent type for emailAdvice ${event}")
      }
    }
    
    Future.sequence(events.map(evt => sendNotification(evt)).toList)
  }
}
