/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.test_only.services

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import cats.data.OptionT

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.SubmissionsDAO
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.apiplatform.modules.test_only.repository.TestApplicationsRepository
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, NotificationRepository, SubscriptionRepository, TermsOfUseInvitationRepository}
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator

@Singleton
class CloneApplicationService @Inject() (
    applicationRepository: ApplicationRepository,
    credentialGenerator: CredentialGenerator,
    subscriptionRepository: SubscriptionRepository,
    notificationRepository: NotificationRepository,
    termsOfUseInvitationRepository: TermsOfUseInvitationRepository,
    submissionsDAO: SubmissionsDAO,
    subsFieldsConector: ApiSubscriptionFieldsConnector,
    testAppRepo: TestApplicationsRepository
  )(implicit ec: ExecutionContext
  ) {

  private val E = EitherTHelper.make[ApplicationId]

  def cloneApplication(appId: ApplicationId)(implicit hc: HeaderCarrier): Future[Either[ApplicationId, StoredApplication]] = {
    val newId       = ApplicationId.random
    val newClientId = ClientId.random

    def cloneSubs(): Future[Set[ApiIdentifier]] = {
      subscriptionRepository.getSubscriptions(appId).flatMap { subsToCopy =>
        Future.sequence(subsToCopy.map(s => subscriptionRepository.add(newId, s))).map(_ => subsToCopy)
      }
    }

    def cloneNotifications(): Future[_] = {
      notificationRepository.find(appId).flatMap { notificationsToCopy =>
        Future.sequence(notificationsToCopy.map(n => notificationRepository.createEntity(n.copy(applicationId = newId))))
      }
    }

    def cloneTermsOfUseInvitation(): Future[_] = {
      OptionT(termsOfUseInvitationRepository.fetch(appId)).flatMap { t =>
        OptionT(termsOfUseInvitationRepository.create(t.copy(applicationId = newId)))
      }
        .value
    }

    def cloneSubmissions(): Future[_] = {
      submissionsDAO.fetchAllForApplication(appId).flatMap { submissionsToCopy =>
        Future.sequence(submissionsToCopy.map { s =>
          submissionsDAO.save(s.copy(id = SubmissionId.random, applicationId = newId))
        })
      }
    }

    def cloneSubsFields(oldClientId: ClientId, subs: Set[ApiIdentifier])(implicit hc: HeaderCarrier): Future[_] = {
      subsFieldsConector.fetchFieldValues(oldClientId).flatMap { subsFields =>
        Future.sequence(
          subsFields.flatMap {
            case (context, versionsMap) =>
              versionsMap.map {
                case (version, fields) =>
                  if (subs.contains(ApiIdentifier(context, version))) {
                    subsFieldsConector.saveFieldValues(newClientId, ApiIdentifier(context, version), fields).map(_ => ())
                  } else {
                    Future.successful(Right((())))
                  }
              }
          }
        )
      }
    }

    (
      for {
        oldApp           <- E.fromOptionF(applicationRepository.fetch(appId), newId)
        suffix            = Instant.now().toEpochMilli().toHexString
        newName           = ApplicationName(s"${oldApp.name} clone $suffix")
        newNormalisedName = newName.value.toLowerCase
        newGatewayId      = credentialGenerator.generate()
        newApp            =
          oldApp.copy(
            id = newId,
            name = newName,
            normalisedName = newNormalisedName,
            wso2ApplicationName = newGatewayId,
            tokens = oldApp.tokens.copy(production = oldApp.tokens.production.copy(clientId = newClientId))
          )
        _                <- E.liftF(testAppRepo.record(newId))
        insertedApp      <- E.liftF(applicationRepository.save(newApp))
        subs             <- E.liftF(cloneSubs())
        _                <- E.liftF(cloneNotifications())
        _                <- E.liftF(cloneTermsOfUseInvitation())
        _                <- E.liftF(cloneSubmissions())
        _                <- E.liftF(cloneSubsFields(oldApp.tokens.production.clientId, subs))
      } yield insertedApp
    )
      .value
      .recover {
        case NonFatal(_) => Left(newId)
      }
  }
}
