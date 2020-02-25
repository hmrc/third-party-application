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
import java.util.UUID.randomUUID

import cats.data.OptionT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.{Logger, LoggerLike}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.thirdpartyapplication.controllers.{ClientSecretRequest, ValidationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.ClientSecret.maskSecret
import uk.gov.hmrc.thirdpartyapplication.models.Environment._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

@Singleton
class CredentialService @Inject()(applicationRepository: ApplicationRepository,
                                  auditService: AuditService,
                                  config: CredentialConfig) {

  val clientSecretLimit = config.clientSecretLimit
  val logger: LoggerLike = Logger
  val generateSecret: () => String = () => randomUUID.toString

  def fetch(applicationId: UUID): Future[Option[ApplicationResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map(
      app => ApplicationResponse(data = app)))
  }

  def fetchCredentials(applicationId: UUID): Future[Option[EnvironmentTokenResponse]] = {
    applicationRepository.fetch(applicationId) map (_.map { app =>
      EnvironmentTokenResponse(app.tokens.production)
    })
  }

  def addClientSecret(id: java.util.UUID, secretRequest: ClientSecretRequest)(implicit hc: HeaderCarrier): Future[EnvironmentTokenResponse] = {
    val newSecretValue = generateSecret()
    for {
      existingApp <- fetchApp(id)
      existingSecrets = existingApp.tokens.production.clientSecrets
      newSecret = ClientSecret(maskSecret(newSecretValue), newSecretValue)
      _ <- saveClientSecretToApp(existingApp, newSecret)
      _ = auditService.audit(ClientSecretAdded, Map("applicationId" -> existingApp.id.toString,
        "newClientSecret" -> newSecret.secret, "clientSecretType" -> "PRODUCTION"))
    } yield EnvironmentTokenResponse(existingApp.tokens.production.copy(clientSecrets = existingSecrets :+ newSecret.copy(name = newSecretValue)))
  }

  private def saveClientSecretToApp(application: ApplicationData, newSecret: ClientSecret): Future[ApplicationData] = {
    val environmentToken = application.tokens.production
    if (environmentToken.clientSecrets.size >= clientSecretLimit) {
      throw new ClientSecretsLimitExceeded
    }
    val updatedEnvironmentToken = environmentToken.copy(clientSecrets = environmentToken.clientSecrets :+ newSecret)

    val updatedApp = application.copy(tokens = ApplicationTokens(updatedEnvironmentToken))
    applicationRepository.save(updatedApp)
  }

  def deleteClientSecrets(id: java.util.UUID, secrets: List[String])(implicit hc: HeaderCarrier): Future[EnvironmentTokenResponse] = {

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

  def validateCredentials(validation: ValidationRequest): OptionT[Future, ApplicationResponse] = {
    def productionTokenIsValid(application: ApplicationData): Boolean = {
      val token = application.tokens.production
      token.clientId == validation.clientId && token.clientSecrets.exists(_.secret == validation.clientSecret)
    }

    def recoverFromFailedUsageDateUpdate(application: ApplicationData): PartialFunction[Throwable, ApplicationData] = {
      case NonFatal(e) =>
        logger.warn("Unable to update the client secret last access date", e)
        application
    }

    for {
      application <- OptionT(applicationRepository.fetchByClientId(validation.clientId)).filter(productionTokenIsValid)
      updatedApplication <-
        OptionT.liftF(applicationRepository.recordClientSecretUsage(application.id.toString, validation.clientSecret)
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