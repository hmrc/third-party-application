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

import cats.data.OptionT
import cats.implicits._

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ClientSecretRequestWithUserId, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class CredentialService @Inject() (
    applicationRepository: ApplicationRepository,
    applicationUpdateService: ApplicationUpdateService,
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

  def addClientSecretNew(applicationId: ApplicationId, request: ClientSecretRequestWithUserId)(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {

    def generateCommand() = {
      val generatedSecret = clientSecretService.generateClientSecret()
      AddClientSecret(instigator = request.userId,
        email = request.actorEmailAddress,
        secretValue = generatedSecret._2,
        clientSecret = generatedSecret._1,
        timestamp = request.timestamp)
    }

    for {
      existingApp <- fetchApp(applicationId)
      _ = if (existingApp.tokens.production.clientSecrets.size >= clientSecretLimit) throw new ClientSecretsLimitExceeded
      addSecretCmd = generateCommand()
      _ <- applicationUpdateService.update(applicationId, addSecretCmd).value
      updatedApplication <- fetchApp(applicationId)
    } yield ApplicationTokenResponse(updatedApplication.tokens.production, addSecretCmd.clientSecret.id, addSecretCmd.secretValue)

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
      _                      = apiPlatformEventService.sendClientSecretAddedEvent(updatedApplication, newSecret.id)
      _                      = auditService.audit(ClientSecretAdded, Map("applicationId" -> applicationId.value.toString, "newClientSecret" -> newSecret.name, "clientSecretType" -> "PRODUCTION"))
      notificationRecipients = existingApp.admins.map(_.emailAddress)

      _ = emailConnector.sendAddedClientSecretNotification(secretRequest.actorEmailAddress, newSecret.name, existingApp.name, notificationRecipients)
    } yield ApplicationTokenResponse(updatedApplication.tokens.production, newSecret.id, newSecretValue)
  }

  @deprecated("remove after client is no longer using the old endpoint")
  def deleteClientSecret(applicationId: ApplicationId, clientSecretId: String, actorEmailAddress: String)(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {
    def audit(applicationId: ApplicationId, clientSecretId: String): Future[AuditResult] =
      auditService.audit(ClientSecretRemoved, Map("applicationId" -> applicationId.value.toString, "removedClientSecret" -> clientSecretId))

    def sendNotification(clientSecret: ClientSecret, app: ApplicationData): Future[HasSucceeded] = {
      emailConnector.sendRemovedClientSecretNotification(actorEmailAddress, clientSecret.name, app.name, app.admins.map(_.emailAddress))
    }

    def findClientSecretToDelete(application: ApplicationData, clientSecretId: String): ClientSecret =
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
