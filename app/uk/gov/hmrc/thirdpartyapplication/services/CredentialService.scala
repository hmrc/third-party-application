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
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
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
                                  config: CredentialConfig)(implicit val ec: ExecutionContext) {

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

      newSecret = clientSecretService.generateClientSecret()

      updatedApplication <- applicationRepository.addClientSecret(id, newSecret)
      _ = auditService.audit(ClientSecretAdded, Map("applicationId" -> id.toString, "newClientSecret" -> newSecret.name, "clientSecretType" -> "PRODUCTION"))
    } yield ApplicationTokenResponse(updatedApplication.tokens.production)
  }

  def deleteClientSecrets(id: UUID, secrets: List[String])(implicit hc: HeaderCarrier): Future[ApplicationTokenResponse] = {

    def audit(clientSecret: ClientSecret) =
      auditService.audit(ClientSecretRemoved, Map("applicationId" -> id.toString, "removedClientSecret" -> clientSecret.secret))

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
    } yield ApplicationTokenResponse(updatedApp.tokens.production)
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
