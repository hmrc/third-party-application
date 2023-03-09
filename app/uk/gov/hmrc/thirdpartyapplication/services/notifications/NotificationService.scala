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

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData

@Singleton
class NotificationService @Inject() (emailConnector: EmailConnector)(implicit val ec: ExecutionContext) extends ApplicationLogger {

  def getActorAsString(actor: Actor): String =
    actor match {
      case Actors.AppCollaborator(emailAddress) => emailAddress.text
      case Actors.GatekeeperUser(userId)        => userId
      case Actors.ScheduledJob(jobId)           => jobId
      case Actors.Unknown                       => "Unknown"
    }

  // scalastyle:off cyclomatic.complexity method.length
  def sendNotifications(
      app: ApplicationData,
      events: NonEmptyList[ApplicationEvent],
      verifiedCollaborators: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[List[HasSucceeded]] = {
    def sendNotification(event: ApplicationEvent) = {
      event match {
        case evt: CollaboratorAddedV2   => CollaboratorAddedNotification.sendCollaboratorAddedNotification(emailConnector, app, evt, verifiedCollaborators)
        case evt: CollaboratorRemovedV2 => CollaboratorRemovedNotification.sendCollaboratorRemovedNotification(emailConnector, app, evt, verifiedCollaborators)

        case evt: ClientSecretAddedV2                               => ClientSecretAddedNotification.sendClientSecretAddedNotification(emailConnector, app, evt)
        case evt: ClientSecretRemovedV2                             => ClientSecretRemovedNotification.sendClientSecretRemovedNotification(emailConnector, app, evt)
        case evt: ProductionAppNameChangedEvent                     => ProductionAppNameChangedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ProductionAppPrivacyPolicyLocationChanged         => StandardChangedNotification.sendAdviceEmail(
            emailConnector,
            app,
            getActorAsString(evt.actor),
            "privacy policy URL",
            evt.oldLocation.describe(),
            evt.newLocation.describe()
          )
        case evt: ProductionLegacyAppPrivacyPolicyLocationChanged   =>
          StandardChangedNotification.sendAdviceEmail(emailConnector, app, getActorAsString(evt.actor), "privacy policy URL", evt.oldUrl, evt.newUrl)
        case evt: ProductionAppTermsConditionsLocationChanged       => StandardChangedNotification.sendAdviceEmail(
            emailConnector,
            app,
            getActorAsString(evt.actor),
            "terms and conditions URL",
            evt.oldLocation.describe,
            evt.newLocation.describe
          )
        case evt: ProductionLegacyAppTermsConditionsLocationChanged =>
          StandardChangedNotification.sendAdviceEmail(emailConnector, app, getActorAsString(evt.actor), "terms and conditions URL", evt.oldUrl, evt.newUrl)
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
        case evt: TermsOfUsePassed                                  => TermsOfUsePassedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ProductionCredentialsApplicationDeleted           => ProductionCredentialsApplicationDeletedNotification.sendAdviceEmail(emailConnector, app, evt)
        case evt: ApplicationDeletedByGatekeeper                    => ApplicationDeletedByGatekeeperNotification.sendAdviceEmail(emailConnector, app, evt)
        case _                                                      => Future.successful(HasSucceeded)
      }
    }

    Future.sequence(events.toList.map(evt => sendNotification(evt)))
  }
  // scalastyle:on cyclomatic.complexity method.length

}
