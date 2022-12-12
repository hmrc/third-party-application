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

package uk.gov.hmrc.thirdpartyapplication.services.notifications

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

import scala.concurrent.Future

object ApplicationDeletedByGatekeeperNotification {
  
  def sendAdviceEmail(emailConnector: EmailConnector, app: ApplicationData, event: UpdateApplicationEvent.ApplicationDeletedByGatekeeper)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val recipients = getRecipients(app)
    emailConnector.sendApplicationDeletedNotification(app.name, app.id, event.requestingAdminEmail, recipients)
  }

  private def getRecipients(app: ApplicationData): Set[String] = {
    app.collaborators.map(_.emailAddress)
  }
}