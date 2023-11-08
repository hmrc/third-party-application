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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import cats.data.OptionT
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.controllers.ValidationRequest
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

@Singleton
class CredentialService @Inject() (
    applicationRepository: ApplicationRepository,
    clientSecretService: ClientSecretService,
    config: CredentialConfig
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

  def validateCredentials(validation: ValidationRequest): OptionT[Future, ApplicationResponse] = {
    def recoverFromFailedUsageDateUpdate(application: StoredApplication): PartialFunction[Throwable, StoredApplication] = {
      case NonFatal(e) =>
        logger.warn("Unable to update the client secret last access date", e)
        application
    }

    for {
      application         <- OptionT(applicationRepository.fetchByClientId(validation.clientId))
      matchedClientSecret <- OptionT(clientSecretService.clientSecretIsValid(application.id, validation.clientSecret, application.tokens.production.clientSecrets))
      updatedApplication  <- OptionT.liftF(applicationRepository.recordClientSecretUsage(application.id, matchedClientSecret.id)
                               .recover(recoverFromFailedUsageDateUpdate(application)))
    } yield ApplicationResponse(data = updatedApplication)
  }

}

case class CredentialConfig(clientSecretLimit: Int)
