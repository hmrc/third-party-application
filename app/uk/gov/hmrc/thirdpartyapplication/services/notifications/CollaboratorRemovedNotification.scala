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

import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.ApplicationEvents.CollaboratorRemovedV2
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication

object CollaboratorRemovedNotification extends NotificationHelpers {

  def sendCollaboratorRemovedNotification(
      emailConnector: EmailConnector,
      app: StoredApplication,
      event: CollaboratorRemovedV2,
      verifiedCollaborators: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier,
      ec: ExecutionContext
    ): Future[HasSucceeded] = {

    val shouldNotifyCollaborator = event.actor match {
      case _: Actors.ScheduledJob => false
      case _                      => true
    }

    for {
      _ <- emailConnector.sendRemovedCollaboratorNotification(event.collaborator.emailAddress, app.name, verifiedCollaborators.filter(onlyAdmins(app)))
      _ <- if (shouldNotifyCollaborator) emailConnector.sendRemovedCollaboratorConfirmation(app.name, Set(event.collaborator.emailAddress)) else Future.successful(HasSucceeded)
    } yield HasSucceeded
  }
}
