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

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.services.events._
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class NotificationService @Inject()(
  nameChangedNotificationEventHdlr: NameChangedNotificationEventHandler
) (implicit val ec: ExecutionContext) extends ApplicationLogger {

  def sendNotifications(app: ApplicationData, events: List[UpdateApplicationEvent with TriggersNotification])(implicit hc: HeaderCarrier): Future[List[HasSucceeded]] = {
    def sendNotification(app: ApplicationData, event: UpdateApplicationEvent with TriggersNotification) = {
      event match {
        case evt: UpdateApplicationEvent.ProductionAppNameChanged => nameChangedNotificationEventHdlr.sendAdviceEmail(app, evt)
      }
    }
    
    Future.sequence(events.map(evt => sendNotification(app, evt)).toList)
  }
}
