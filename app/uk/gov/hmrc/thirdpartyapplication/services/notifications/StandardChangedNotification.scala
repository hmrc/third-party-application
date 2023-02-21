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

package uk.gov.hmrc.thirdpartyapplication.services.notifications

import scala.concurrent.Future

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

object StandardChangedNotification {

  def sendAdviceEmail(
      emailConnector: EmailConnector,
      app: ApplicationData,
      requestingAdminEmail: String,
      fieldName: String,
      previousValue: String,
      newValue: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    val recipients = getRecipients(app) ++ getResponsibleIndividual(app)
    emailConnector.sendChangeOfApplicationDetails(requestingAdminEmail, app.name, fieldName, previousValue, newValue, recipients)
  }

  private def getRecipients(app: ApplicationData): Set[LaxEmailAddress] = {
    app.collaborators.map(_.emailAddress)
  }

  private def getResponsibleIndividual(app: ApplicationData): Set[LaxEmailAddress] = {
    app.access match {
      case Standard(_, _, _, _, _, Some(importantSubmissionData)) => Set(importantSubmissionData.responsibleIndividual.emailAddress)
      case _                                                      => Set()
    }
  }
}
