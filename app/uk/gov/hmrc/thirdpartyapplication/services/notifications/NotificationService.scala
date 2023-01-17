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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.Actor._
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class NotificationService @Inject() (emailConnector: EmailConnector)(implicit val ec: ExecutionContext) extends ApplicationLogger {

  def sendNotifications(app: ApplicationData, events: List[UpdateApplicationEvent with TriggersNotification])(implicit hc: HeaderCarrier): Future[List[HasSucceeded]] = {
    def sendNotification(app: ApplicationData, event: UpdateApplicationEvent with TriggersNotification) = {
      event match {
        case evt: ClientSecretAdded                                 => ClientSecretAddedNotification.sendClientSecretAddedNotification(emailConnector, app, evt)
        case evt: ClientSecretRemoved                               => ClientSecretRemovedNotification.sendClientSecretRemovedNotification(emailConnector, app, evt)
        case evt: ProductionAppNameChanged                          => ProductionAppNameChangedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ProductionAppPrivacyPolicyLocationChanged         => StandardChangedNotification.sendAdviceEmail(
            emailConnector,
            app,
            getCollaboratorAsString(evt.actor),
            "privacy policy URL",
            PrivacyPolicyLocation.describe(evt.oldLocation),
            PrivacyPolicyLocation.describe(evt.newLocation)
          )
        case evt: ProductionLegacyAppPrivacyPolicyLocationChanged   =>
          StandardChangedNotification.sendAdviceEmail(emailConnector, app, getCollaboratorAsString(evt.actor), "privacy policy URL", evt.oldUrl, evt.newUrl)
        case evt: ProductionAppTermsConditionsLocationChanged       => StandardChangedNotification.sendAdviceEmail(
            emailConnector,
            app,
            getCollaboratorAsString(evt.actor),
            "terms and conditions URL",
            TermsAndConditionsLocation.describe(evt.oldLocation),
            TermsAndConditionsLocation.describe(evt.newLocation)
          )
        case evt: ProductionLegacyAppTermsConditionsLocationChanged =>
          StandardChangedNotification.sendAdviceEmail(emailConnector, app, getCollaboratorAsString(evt.actor), "terms and conditions URL", evt.oldUrl, evt.newUrl)
        case evt: ResponsibleIndividualVerificationStarted          => VerifyResponsibleIndividualUpdateNotification.sendAdviceEmail(emailConnector, evt)
        case evt: ResponsibleIndividualChanged                      => ResponsibleIndividualChangedNotification.sendAdviceEmail(
            emailConnector,
            app,
            evt.previousResponsibleIndividualEmail,
            evt.requestingAdminName,
            evt.previousResponsibleIndividualName,
            evt.newResponsibleIndividualName
          )
        case evt: ResponsibleIndividualChangedToSelf                => ResponsibleIndividualChangedNotification.sendAdviceEmail(
            emailConnector,
            app,
            evt.previousResponsibleIndividualEmail,
            evt.requestingAdminName,
            evt.previousResponsibleIndividualName,
            evt.requestingAdminName
          )
        case evt: ResponsibleIndividualDeclined                     => ResponsibleIndividualDeclinedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ResponsibleIndividualDeclinedUpdate               => ResponsibleIndividualDeclinedUpdateNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ResponsibleIndividualDidNotVerify                 => ResponsibleIndividualDidNotVerifyNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ProductionCredentialsApplicationDeleted           => ProductionCredentialsApplicationDeletedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ApplicationDeletedByGatekeeper                    => ApplicationDeletedByGatekeeperNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: CollaboratorAdded                                 => CollaboratorAddedNotification.sendCollaboratorAddedNotification(emailConnector, app, evt)
        case evt: CollaboratorRemoved                               => CollaboratorRemovedNotification.sendCollaboratorRemovedNotification(emailConnector, app, evt)
      }
    }

    Future.sequence(events.map(evt => sendNotification(app, evt)))
  }

}
