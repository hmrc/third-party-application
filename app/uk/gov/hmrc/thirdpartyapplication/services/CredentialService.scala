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
import javax.inject.Inject

import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class CredentialService @Inject()(applicationRepository: ApplicationRepository,
                                  auditService: AuditService,
                                  trustedApplications: TrustedApplications,
                                  applicationResponseCreator: ApplicationResponseCreator,
                                  config: CredentialConfig) {

  val clientSecretLimit = config.clientSecretLimit

  def fetch(applicationId: UUID): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map(
      app => ApplicationResponse(data = app, clientId = None, trusted = trustedApplications.isTrusted(app))))
  }

  def fetchCredentials(applicationId: UUID): Future[Option[ApplicationTokensResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map { app =>
      ApplicationTokensResponse.create(app.tokens)
    })
  }

  def fetchWso2Credentials(clientId: String): Future[Option[Wso2Credentials]] = {
    applicationRepository.fetchByClientId(clientId) map (_.flatMap { app =>
      Seq(app.tokens.production, app.tokens.sandbox)
        .find(_.clientId == clientId)
        .map(token => Wso2Credentials(token.clientId, token.accessToken, token.wso2ClientSecret))
    })
  }

  def addClientSecret(id: java.util.UUID, secretRequest: ClientSecretRequest)(implicit hc: HeaderCarrier): Future[ApplicationTokensResponse] = {
    for {
      app <- fetchApp(id)
      secret = ClientSecret(secretRequest.name)
      updatedApp = addClientSecretToApp(app, secret)
      savedApp <- applicationRepository.save(updatedApp)
      _ = auditService.audit(ClientSecretAdded, Map("applicationId" -> app.id.toString,
        "newClientSecret" -> secret.secret, "clientSecretType" -> "PRODUCTION"))
    } yield ApplicationTokensResponse.create(savedApp.tokens)
  }

  private def addClientSecretToApp(application: ApplicationData, secret: ClientSecret) = {
    val environmentTokens = application.tokens.environmentToken(Environment.PRODUCTION)
    if (environmentTokens.clientSecrets.size >= clientSecretLimit) {
      throw new ClientSecretsLimitExceeded
    }
    val updatedEnvironmentTokens = environmentTokens.copy(clientSecrets = environmentTokens.clientSecrets :+ secret)

    application.copy(tokens = ApplicationTokens(updatedEnvironmentTokens, application.tokens.sandbox))

  }

  def deleteClientSecrets(id: java.util.UUID, secrets: Seq[String])(implicit hc: HeaderCarrier): Future[ApplicationTokensResponse] = {

    def audit(clientSecret: ClientSecret) = {
      auditService.audit(ClientSecretRemoved, Map("applicationId" -> id.toString,
        "removedClientSecret" -> clientSecret.secret))
    }

    def updateApp(app: ApplicationData): (ApplicationData, Set[ClientSecret]) = {
      val numberOfSecretsToDelete = secrets.length
      val existingSecrets= app.tokens.production.clientSecrets
      val updatedSecrets = existingSecrets.filterNot(secret => secrets.contains(secret.secret))
      if (existingSecrets.length - updatedSecrets.length != numberOfSecretsToDelete) {
        throw new NotFoundException ("Cannot find all secrets to delete")
      }
      if (updatedSecrets.isEmpty) {
        throw new IllegalArgumentException ("Cannot delete all client secrets")
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
    } yield ApplicationTokensResponse.create(updatedApp.tokens)
  }

  def validateCredentials(validation: ValidationRequest): Future[Option[Environment]] = {
    applicationRepository.fetchByClientId(validation.clientId) map (_.flatMap { app =>
      Seq(app.tokens.production -> PRODUCTION, app.tokens.sandbox -> SANDBOX)
        .find(t =>
        t._1.clientId == validation.clientId && t._1.clientSecrets.exists(_.secret == validation.clientSecret)
        ).map(_._2)
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