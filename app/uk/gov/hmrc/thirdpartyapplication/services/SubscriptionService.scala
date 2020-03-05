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

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector.ApiDefinitionConnector
import uk.gov.hmrc.thirdpartyapplication.models.ApiStatus.ALPHA
import uk.gov.hmrc.thirdpartyapplication.models.JsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}

@Singleton
class SubscriptionService @Inject()(applicationRepository: ApplicationRepository,
                                    subscriptionRepository: SubscriptionRepository,
                                    apiDefinitionConnector: ApiDefinitionConnector,
                                    auditService: AuditService,
                                    apiGatewayStore: ApiGatewayStore) {

  val IgnoredContexts: List[String] = List("sso-in/sso", "web-session/sso-api")

  def searchCollaborators(context:String, version:String, partialEmailMatch: Option[String]):Future[List[String]] = {
    subscriptionRepository.searchCollaborators(context, version, partialEmailMatch)
  }


  def fetchAllSubscriptions(): Future[List[SubscriptionData]] = subscriptionRepository.findAll()

  def fetchAllSubscriptionsForApplication(applicationId: UUID)(implicit hc: HeaderCarrier): Future[List[ApiSubscription]] = {
    def fetchApis: Future[List[ApiDefinition]] = apiDefinitionConnector.fetchAllAPIs(applicationId) map {
      _.map(api => api.copy(versions = api.versions.filterNot(_.status == ALPHA)))
        .filterNot(_.versions.isEmpty)
    }

    for {
      _ <- fetchApp(applicationId) // Determine whether application exists and fail if it doesn't
      apis <- fetchApis
      subscriptions <- subscriptionRepository.getSubscriptions(applicationId)
    } yield apis.map(api => ApiSubscription.from(api, subscriptions))
  }

  def isSubscribed(applicationId: UUID, api: APIIdentifier): Future[Boolean] = {
    subscriptionRepository.isSubscribed(applicationId, api)
  }

  def createSubscriptionForApplication(applicationId: UUID, apiIdentifier: APIIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    def versionSubscriptionFuture: Future[Option[VersionSubscription]] = fetchAllSubscriptionsForApplication(applicationId) map { apis =>
      apis.find(_.context == apiIdentifier.context) flatMap (_.versions.find(_.version.version == apiIdentifier.version))
    }

    def fetchAppFuture = fetchApp(applicationId)

    def checkVersionSubscription(app: ApplicationData, versionSubscriptionMaybe: Option[VersionSubscription]): Unit = {
      versionSubscriptionMaybe match {
        case None => throw new NotFoundException(s"API $apiIdentifier is not available for application $applicationId")
        case Some(versionSubscription) if versionSubscription.subscribed => throw SubscriptionAlreadyExistsException(app.name, apiIdentifier)
        case _ =>
      }
    }

    for {
      versionSubscription <- versionSubscriptionFuture
      app <- fetchAppFuture
      _ = checkVersionSubscription(app, versionSubscription)
      _ <- subscriptionRepository.add(applicationId, apiIdentifier)
      _ <- auditSubscription(Subscribed, applicationId, apiIdentifier)
    } yield HasSucceeded
  }

  def removeSubscriptionForApplication(applicationId: UUID, apiIdentifier: APIIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    for {
      _ <- fetchApp(applicationId)
      _ <- subscriptionRepository.remove(applicationId, apiIdentifier)
      _ <- auditSubscription(Unsubscribed, applicationId, apiIdentifier)
    } yield HasSucceeded
  }

  private def auditSubscription(action: AuditAction, applicationId: UUID, api: APIIdentifier)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    auditService.audit(action, Map(
      "applicationId" -> applicationId.toString,
      "apiVersion" -> api.version,
      "apiContext" -> api.context
    ))
  }

  private def fetchApp(applicationId: UUID) = {
    applicationRepository.fetch(applicationId).flatMap {
      case Some(app) => successful(app)
      case _ => failed(new NotFoundException(s"Application not found for id: $applicationId"))
    }
  }

}
