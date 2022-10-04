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

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector

@Singleton
class NotificationService @Inject()(emailConnector: EmailConnector)(implicit val ec: ExecutionContext) extends ApplicationLogger {

  def sendNotifications(app: ApplicationData, events: List[UpdateApplicationEvent with TriggersNotification])(implicit hc: HeaderCarrier): Future[List[HasSucceeded]] = {
    def sendNotification(app: ApplicationData, event: UpdateApplicationEvent with TriggersNotification) = {
      event match {
        case evt: UpdateApplicationEvent.ProductionAppNameChanged => ProductionAppNameChangedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: UpdateApplicationEvent.ProductionAppPrivacyPolicyLocationChanged => ApplicationChangedNoValueNotification.sendAdviceEmail(emailConnector, app, evt.requestingAdminEmail, "privacy policy URL")
        case evt: UpdateApplicationEvent.ProductionLegacyAppPrivacyPolicyLocationChanged => ApplicationChangedNoValueNotification.sendAdviceEmail(emailConnector, app, evt.requestingAdminEmail, "privacy policy URL")
        case evt: UpdateApplicationEvent.ProductionAppTermsConditionsLocationChanged => ApplicationChangedNoValueNotification.sendAdviceEmail(emailConnector, app, evt.requestingAdminEmail, "terms and conditions URL")
        case evt: UpdateApplicationEvent.ProductionLegacyAppTermsConditionsLocationChanged => ApplicationChangedNoValueNotification.sendAdviceEmail(emailConnector, app, evt.requestingAdminEmail, "terms and conditions URL")
        case evt: UpdateApplicationEvent.ResponsibleIndividualVerificationStarted => VerifyResponsibleIndividualUpdateNotification.sendAdviceEmail(emailConnector, evt)
        case evt: UpdateApplicationEvent.ResponsibleIndividualChanged => ResponsibleIndividualChangedNotification.sendAdviceEmail(emailConnector, app, evt.previousResponsibleIndividualEmail, evt.requestingAdminName, evt.previousResponsibleIndividualName, evt.newResponsibleIndividualName)
        case evt: UpdateApplicationEvent.ResponsibleIndividualChangedToSelf => ResponsibleIndividualChangedNotification.sendAdviceEmail(emailConnector, app, evt.previousResponsibleIndividualEmail, evt.requestingAdminName, evt.previousResponsibleIndividualName, evt.requestingAdminName)
        case evt: UpdateApplicationEvent.ResponsibleIndividualDeclined => ResponsibleIndividualDeclinedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: UpdateApplicationEvent.ResponsibleIndividualDeclinedUpdate => ResponsibleIndividualDeclinedUpdateNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: UpdateApplicationEvent.ResponsibleIndividualDidNotVerify => ResponsibleIndividualDidNotVerifyNotification.sendAdviceEmail(emailConnector, app, evt)
      }
    }
    
    Future.sequence(events.map(evt => sendNotification(app, evt)))
  }

}
