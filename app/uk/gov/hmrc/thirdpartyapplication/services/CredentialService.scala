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

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import cats.data.OptionT
import cats.implicits._

import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientSecret}
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ClientSecretDetails
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ClientSecretRequestWithActor, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

@Singleton
class CredentialService @Inject() (
    applicationRepository: ApplicationRepository,
    applicationCommandDispatcher: ApplicationCommandDispatcher,
    auditService: AuditService,
    clientSecretService: ClientSecretService,
    config: CredentialConfig,
    apiPlatformEventService: ApiPlatformEventService,
    emailConnector: EmailConnector
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  val clientSecretLimit = config.clientSecretLimit

  def fetch(applicationId: ApplicationId): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map(app => ApplicationResponse(data = app)))
  }

  def fetchCredentials(applicationId: ApplicationId): Future[Option[ApplicationTokenResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map { app =>
      ApplicationTokenResponse(app.tokens.production)
    })
  }

  def addClientSecretNew(applicationId: ApplicationId, request: ClientSecretRequestWithActor)(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {

    def generateCommand(csd: ClientSecretData) = {
      val cmdClientSecret = ClientSecretDetails(csd.name, csd.createdOn, csd.lastAccess, ClientSecret.Id(UUID.fromString(csd.id)), csd.hashedSecret)
      AddClientSecret(actor = request.actor, cmdClientSecret, timestamp = request.timestamp)
    }

    for {
      existingApp                <- fetchApp(applicationId)
      _                           = if (existingApp.tokens.production.clientSecrets.size >= clientSecretLimit) throw new ClientSecretsLimitExceeded
      (clientSecret, secretValue) = clientSecretService.generateClientSecret()
      addSecretCmd                = generateCommand(clientSecret)
      _                          <- applicationCommandDispatcher.dispatch(applicationId, addSecretCmd, Set.empty).value
      updatedApplication         <- fetchApp(applicationId)
    } yield ApplicationTokenResponse(updatedApplication.tokens.production, addSecretCmd.clientSecret.id.value.toString, secretValue)
  }

  @deprecated("remove after client is no longer using the old endpoint")
  def addClientSecret(applicationId: ApplicationId, secretRequest: ClientSecretRequest)(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {
    for {
      existingApp <- fetchApp(applicationId)
      _            = if (existingApp.tokens.production.clientSecrets.size >= clientSecretLimit) throw new ClientSecretsLimitExceeded

      generatedSecret = clientSecretService.generateClientSecret()
      newSecret       = generatedSecret._1
      newSecretValue  = generatedSecret._2

      updatedApplication    <- applicationRepository.addClientSecret(applicationId, newSecret)
      _                     <- apiPlatformEventService.sendClientSecretAddedEvent(updatedApplication, newSecret.id)
      _                      = auditService.audit(ClientSecretAddedAudit, Map("applicationId" -> applicationId.value.toString, "newClientSecret" -> newSecret.name, "clientSecretType" -> "PRODUCTION"))
      notificationRecipients = existingApp.admins.map(_.emailAddress)

      _ = emailConnector.sendAddedClientSecretNotification(secretRequest.actorEmailAddress, newSecret.name, existingApp.name, notificationRecipients)
    } yield ApplicationTokenResponse(updatedApplication.tokens.production, newSecret.id, newSecretValue)
  }

  @deprecated("remove after client is no longer using the old endpoint")
  def deleteClientSecret(applicationId: ApplicationId, clientSecretId: String, actorEmailAddress: LaxEmailAddress)(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {
    def audit(applicationId: ApplicationId, clientSecretId: String): Future[AuditResult] =
      auditService.audit(ClientSecretRemovedAudit, Map("applicationId" -> applicationId.value.toString, "removedClientSecret" -> clientSecretId))

    def sendNotification(clientSecret: ClientSecretData, app: ApplicationData): Future[HasSucceeded] = {
      emailConnector.sendRemovedClientSecretNotification(actorEmailAddress, clientSecret.name, app.name, app.admins.map(_.emailAddress))
    }

    def findClientSecretToDelete(application: ApplicationData, clientSecretId: String): ClientSecretData =
      application.tokens.production.clientSecrets
        .find(_.id == clientSecretId)
        .getOrElse(throw new NotFoundException(s"Client Secret Id [$clientSecretId] not found in Application [${applicationId.value}]"))

    for {
      application         <- fetchApp(applicationId)
      clientSecretToUpdate = findClientSecretToDelete(application, clientSecretId)
      updatedApplication  <- applicationRepository.deleteClientSecret(applicationId, clientSecretId)
      _                   <- audit(applicationId, clientSecretId)
      _                   <- apiPlatformEventService.sendClientSecretRemovedEvent(updatedApplication, clientSecretId)
      _                   <- sendNotification(clientSecretToUpdate, updatedApplication)
    } yield ApplicationTokenResponse(updatedApplication.tokens.production)

  }

  def validateCredentials(validation: ValidationRequest): OptionT[Future, ApplicationResponse] = {
    def recoverFromFailedUsageDateUpdate(application: ApplicationData): PartialFunction[Throwable, ApplicationData] = {
      case NonFatal(e) =>
        logger.warn("Unable to update the client secret last access date", e)
        application
    }

    for {
      application         <- OptionT(applicationRepository.fetchByClientId(validation.clientId))
      matchedClientSecret <-
        OptionT(clientSecretService.clientSecretIsValid(application.id, validation.clientSecret, application.tokens.production.clientSecrets))
      updatedApplication  <-
        OptionT.liftF(applicationRepository.recordClientSecretUsage(application.id, matchedClientSecret.id)
          .recover(recoverFromFailedUsageDateUpdate(application)))
    } yield ApplicationResponse(data = updatedApplication)

  }

  private def fetchApp(applicationId: ApplicationId) = {
    val notFoundException = new NotFoundException(s"application not found for id: ${applicationId.value}")
    applicationRepository.fetch(applicationId).flatMap {
      case None      => Future.failed(notFoundException)
      case Some(app) => Future.successful(app)
    }
  }

}

case class CredentialConfig(clientSecretLimit: Int)
