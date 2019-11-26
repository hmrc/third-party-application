/*
 * Copyright 2019 HM Revenue & Customs
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
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class CredentialService @Inject()(applicationRepository: ApplicationRepository,
                                  auditService: AuditService,
                                  applicationResponseCreator: ApplicationResponseCreator,
                                  config: CredentialConfig) {

  val clientSecretLimit = config.clientSecretLimit

  def fetch(applicationId: UUID): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map(
      app => ApplicationResponse(data = app)))
  }

  def fetchCredentials(applicationId: UUID): Future[Option[EnvironmentTokenResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map { app =>
      EnvironmentTokenResponse(app.tokens.production)
    })
  }

  def fetchWso2Credentials(clientId: String): Future[Option[Wso2Credentials]] = {
    applicationRepository.fetchByClientId(clientId) map (_.flatMap { app =>
      val environmentToken = app.tokens.production
      if (environmentToken.clientId == clientId) {
        Some(Wso2Credentials(environmentToken.clientId, environmentToken.accessToken, environmentToken.wso2ClientSecret))
      } else {
        None
      }
    })
  }

  def addClientSecret(id: java.util.UUID, secretRequest: ClientSecretRequest)(implicit hc: HeaderCarrier): Future[EnvironmentTokenResponse] = {
    for {
      app <- fetchApp(id)
      secret = ClientSecret(secretRequest.name)
      updatedApp = addClientSecretToApp(app, secret)
      savedApp <- applicationRepository.save(updatedApp)
      _ = auditService.audit(ClientSecretAdded, Map("applicationId" -> app.id.toString,
        "newClientSecret" -> secret.secret, "clientSecretType" -> "PRODUCTION"))
    } yield EnvironmentTokenResponse(savedApp.tokens.production)
  }

  private def addClientSecretToApp(application: ApplicationData, secret: ClientSecret) = {
    val environmentToken = application.tokens.production
    if (environmentToken.clientSecrets.size >= clientSecretLimit) {
      throw new ClientSecretsLimitExceeded
    }
    val updatedEnvironmentToken = environmentToken.copy(clientSecrets = environmentToken.clientSecrets :+ secret)

    application.copy(tokens = ApplicationTokens(updatedEnvironmentToken))

  }

  def deleteClientSecrets(id: java.util.UUID, secrets: Seq[String])(implicit hc: HeaderCarrier): Future[EnvironmentTokenResponse] = {

    def audit(clientSecret: ClientSecret) = {
      auditService.audit(ClientSecretRemoved, Map("applicationId" -> id.toString,
        "removedClientSecret" -> clientSecret.secret))
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
    } yield EnvironmentTokenResponse(updatedApp.tokens.production)
  }

  def validateCredentials(validation: ValidationRequest): Future[Option[Environment]] = {
    applicationRepository.fetchByClientId(validation.clientId) map (_.flatMap { app =>
      val environmentToken = app.tokens.production
      if (environmentToken.clientId == validation.clientId && environmentToken.clientSecrets.exists(_.secret == validation.clientSecret)) {
        Some(PRODUCTION)
      } else {
        None
      }
    })
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

case class ApplicationNameValidationConfig(nameBlackList: Seq[String], validateForDuplicateAppNames: Boolean)