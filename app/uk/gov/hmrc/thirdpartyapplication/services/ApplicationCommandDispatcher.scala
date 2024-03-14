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

package uk.gov.hmrc.thirdpartyapplication.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services.commands._
import uk.gov.hmrc.thirdpartyapplication.services.commands.clientsecret.ClientSecretCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.collaborator.CollaboratorCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.delete.DeleteCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.grantlength.GrantLengthCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.ipallowlist.IpAllowListCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.namedescription.NameDescriptionCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.policy.PolicyCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.ratelimit.RateLimitCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.redirecturi.RedirectUriCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.submission.SubmissionCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.commands.subscription.SubscriptionCommandsProcessor
import uk.gov.hmrc.thirdpartyapplication.services.notifications.NotificationService

@Singleton
class ApplicationCommandDispatcher @Inject() (
    applicationRepository: ApplicationRepository,
    notificationService: NotificationService,
    apiPlatformEventService: ApiPlatformEventService,
    auditService: AuditService,
    clientSecretCommandsProcessor: ClientSecretCommandsProcessor,
    collaboratorCommandsProcessor: CollaboratorCommandsProcessor,
    deleteCommandsProcessor: DeleteCommandsProcessor,
    grantLengthCommandsProcessor: GrantLengthCommandsProcessor,
    ipAllowListCommandsProcessor: IpAllowListCommandsProcessor,
    nameDescriptionCommandsProcessor: NameDescriptionCommandsProcessor,
    policyCommandsProcessor: PolicyCommandsProcessor,
    rateLimitCommandsProcessor: RateLimitCommandsProcessor,
    redirectUriCommandsProcessor: RedirectUriCommandsProcessor,
    submissionsCommandsProcessor: SubmissionCommandsProcessor,
    subscriptionCommandsProcessor: SubscriptionCommandsProcessor
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  import cats.implicits._
  import CommandHandler._

  val E = EitherTHelper.make[CommandHandler.Failures]

  def dispatch(applicationId: ApplicationId, command: ApplicationCommand, verifiedCollaborators: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): AppCmdResultT = {
    for {
      app               <- E.fromOptionF(applicationRepository.fetch(applicationId), NonEmptyList.one(CommandFailures.ApplicationNotFound))
      updateResults     <- process(app, command)
      (savedApp, events) = updateResults

      _ <- E.liftF(apiPlatformEventService.applyEvents(events))
      _ <- E.liftF(auditService.applyEvents(savedApp, events))
      _ <- E.liftF(notificationService.sendNotifications(savedApp, events, verifiedCollaborators))
    } yield (savedApp, events)
  }

  // scalastyle:off cyclomatic.complexity
  private def process(app: StoredApplication, command: ApplicationCommand)(implicit hc: HeaderCarrier): AppCmdResultT = {
    command match {
      case cmd: ClientSecretCommand    => clientSecretCommandsProcessor.process(app, cmd)
      case cmd: CollaboratorCommand    => collaboratorCommandsProcessor.process(app, cmd)
      case cmd: DeleteCommand          => deleteCommandsProcessor.process(app, cmd)
      case cmd: GrantLengthCommand     => grantLengthCommandsProcessor.process(app, cmd)
      case cmd: IpAllowListCommand     => ipAllowListCommandsProcessor.process(app, cmd)
      case cmd: NameDescriptionCommand => nameDescriptionCommandsProcessor.process(app, cmd)
      case cmd: PolicyCommand          => policyCommandsProcessor.process(app, cmd)
      case cmd: RateLimitCommand       => rateLimitCommandsProcessor.process(app, cmd)
      case cmd: RedirectCommand        => redirectUriCommandsProcessor.process(app, cmd)
      case cmd: SubmissionCommand      => submissionsCommandsProcessor.process(app, cmd)
      case cmd: SubscriptionCommand    => subscriptionCommandsProcessor.process(app, cmd)

    }
  }
  // scalastyle:on cyclomatic.complexity
}
