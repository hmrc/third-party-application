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

package uk.gov.hmrc.thirdpartyapplication.services.events

import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.domain.models.Standard
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}
import javax.inject.{Inject, Singleton}

@Singleton
class NameChangedNotificationEventHandler @Inject()(
  emailConnector: EmailConnector
)(implicit val ec: ExecutionContext) {

  def sendAdviceEmail(app: ApplicationData, event: UpdateApplicationEvent.ProductionAppNameChanged)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val recipients = getRecipients(app) ++ getResponsibleIndividual(app)
    emailConnector.sendChangeOfApplicationName(event.requestingAdminEmail, event.oldAppName, event.newAppName, recipients)
  }

  def getRecipients(app: ApplicationData): Set[String] = {
    app.collaborators.map(_.emailAddress)
  }

  def getResponsibleIndividual(app: ApplicationData): Set[String] = {
    app.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => Set(importantSubmissionData.responsibleIndividual.emailAddress.value)
      case _ => Set()
    }
  }
}