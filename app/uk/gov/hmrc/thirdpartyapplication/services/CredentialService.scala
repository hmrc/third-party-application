/*
 * Copyright 2020 HM Revenue & Customs
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

import cats.data.OptionT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.{Logger, LoggerLike}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{ClientSecretsLimitExceeded, _}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class CredentialService @Inject()(applicationRepository: ApplicationRepository,
                                  auditService: AuditService,
                                  clientSecretService: ClientSecretService,
                                  config: CredentialConfig,
                                  emailConnector: EmailConnector)(implicit val ec: ExecutionContext) {

  val clientSecretLimit = config.clientSecretLimit
  val logger: LoggerLike = Logger

  def fetch(applicationId: UUID): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map(
      app => ApplicationResponse(data = app)))
  }

  def fetchCredentials(applicationId: UUID): Future[Option[ApplicationTokenResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map { app =>
      ApplicationTokenResponse(app.tokens.production)
    })
  }

  def addClientSecret(id: UUID, secretRequest: ClientSecretRequest)(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {
    for {
      existingApp <- fetchApp(id)
      _ = if(existingApp.tokens.production.clientSecrets.size >= clientSecretLimit) throw new ClientSecretsLimitExceeded

      generatedSecret = clientSecretService.generateClientSecret()
      newSecret = generatedSecret._1
      newSecretValue = generatedSecret._2

      updatedApplication <- applicationRepository.addClientSecret(id, newSecret)
      _ = auditService.audit(ClientSecretAdded, Map("applicationId" -> id.toString, "newClientSecret" -> newSecret.name, "clientSecretType" -> "PRODUCTION"))
      notificationRecipients = existingApp.admins.map(_.emailAddress)
      _ = emailConnector.sendAddedClientSecretNotification(secretRequest.actorEmailAddress, newSecret.name, existingApp.name, notificationRecipients)
    } yield ApplicationTokenResponse(updatedApplication.tokens.production, newSecret.id, newSecretValue)
  }

  def deleteClientSecrets(id: UUID, actorEmailAddress: String, secrets: List[String])(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {
    def audit(clientSecret: ClientSecret): Future[AuditResult] =
      auditService.audit(ClientSecretRemoved, Map("applicationId" -> id.toString, "removedClientSecret" -> clientSecret.secret))

    def sendNotification(clientSecret: ClientSecret, app: ApplicationData): Future[HttpResponse] = {
      emailConnector.sendRemovedClientSecretNotification(actorEmailAddress, clientSecret.name, app.name, app.admins.map(_.emailAddress))
    }

    def updateApp(app: ApplicationData): (ApplicationData, Set[ClientSecret]) = {
      val numberOfSecretsToDelete = secrets.length
      val existingSecrets = app.tokens.production.clientSecrets
      val updatedSecrets = existingSecrets.filterNot(secret => secrets.contains(secret.secret))
      if (existingSecrets.length - updatedSecrets.length != numberOfSecretsToDelete) {
        throw new NotFoundException("Cannot find all secrets to delete")
      }
      if (updatedSecrets.isEmpty) {
        throw new IllegalArgumentException("Cannot delete all client secrets")
      }
      val updatedProductionToken = app.tokens.production.copy(clientSecrets = updatedSecrets)
      val updatedTokens = app.tokens.copy(production = updatedProductionToken)
      val updatedApp = app.copy(tokens = updatedTokens)
      val removedSecrets = existingSecrets.toSet -- updatedSecrets.toSet
      (updatedApp, removedSecrets)
    }

    for {
      app <- fetchApp(id)
      (updatedApp, removedSecrets) = updateApp(app)
      _ <- applicationRepository.save(updatedApp)
      _ <- Future.traverse(removedSecrets)(audit)
      _ <- Future.traverse(removedSecrets)(sendNotification(_, app))
    } yield ApplicationTokenResponse(updatedApp.tokens.production)
  }

  def deleteClientSecret(applicationId: UUID,
                         clientSecretId: String,
                         actorEmailAddress: String)(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {
    def audit(applicationId: UUID, clientSecretId: String): Future[AuditResult] =
      auditService.audit(ClientSecretRemoved, Map("applicationId" -> applicationId.toString, "removedClientSecret" -> clientSecretId))

    def sendNotification(clientSecret: ClientSecret, app: ApplicationData): Future[HttpResponse] = {
      emailConnector.sendRemovedClientSecretNotification(actorEmailAddress, clientSecret.name, app.name, app.admins.map(_.emailAddress))
    }

    def findClientSecretToDelete(application: ApplicationData, clientSecretId: String): ClientSecret =
      application.tokens.production.clientSecrets
        .find(_.id == clientSecretId)
        .getOrElse(throw new NotFoundException(s"Client Secret Id [$clientSecretId] not found in Application [$applicationId]"))

    for {
      application <- fetchApp(applicationId)
      clientSecretToUpdate = findClientSecretToDelete(application, clientSecretId)
      updatedApplication <- applicationRepository.deleteClientSecret(applicationId, clientSecretId)
      _ <- audit(applicationId, clientSecretId)
      _ <- sendNotification(clientSecretToUpdate, updatedApplication)
    } yield ApplicationTokenResponse(updatedApplication.tokens.production)

  }

  def validateCredentials(validation: ValidationRequest): OptionT[Future, ApplicationResponse] = {
    def recoverFromFailedUsageDateUpdate(application: ApplicationData): PartialFunction[Throwable, ApplicationData] = {
      case NonFatal(e) =>
        logger.warn("Unable to update the client secret last access date", e)
        application
    }

    for {
      application <- OptionT(applicationRepository.fetchByClientId(validation.clientId))
      matchedClientSecret <- OptionT(clientSecretService.clientSecretIsValid(validation.clientSecret, application.tokens.production.clientSecrets))
      updatedApplication <-
        OptionT.liftF(applicationRepository.recordClientSecretUsage(application.id, matchedClientSecret.id)
          .recover(recoverFromFailedUsageDateUpdate(application)))
    } yield ApplicationResponse(data = updatedApplication)

  }

  private def fetchApp(applicationId: UUID) = {
    val notFoundException = new NotFoundException(s"application not found for id: $applicationId")
    applicationRepository.fetch(applicationId).flatMap {
      case None => Future.failed(notFoundException)
      case Some(app) => Future.successful(app)
    }
  }

}

case class CredentialConfig(clientSecretLimit: Int)

case class ApplicationNameValidationConfig(nameBlackList: List[String], validateForDuplicateAppNames: Boolean)
