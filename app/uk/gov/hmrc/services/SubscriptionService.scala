/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.services

import java.util.UUID
import javax.inject.Inject

import play.api.Logger
import uk.gov.hmrc.config.AppContext
import uk.gov.hmrc.connector.APIDefinitionConnector
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.models._
import uk.gov.hmrc.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.services.AuditAction._
import uk.gov.hmrc.models.JsonFormatters._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.{failed, sequence, successful}

class SubscriptionService @Inject()(applicationRepository: ApplicationRepository,
                                    subscriptionRepository: SubscriptionRepository,
                                    apiDefinitionConnector: APIDefinitionConnector,
                                    auditService: AuditService,
                                    wso2APIStore: WSO2APIStore,
                                    appContext: AppContext){

  val trustedApplications = appContext.trustedApplications

  def fetchAllSubscriptions(): Future[List[SubscriptionData]] = subscriptionRepository.findAll()

  def fetchAllSubscriptionsForApplication(applicationId: UUID)(implicit hc: HeaderCarrier) = {
    val fetchApis: Future[Seq[APIDefinition]] = apiDefinitionConnector.fetchAllAPIs(applicationId) map {
      apis => apis.filter(api => trustedApplications.contains(applicationId.toString) || !api.requiresTrust.getOrElse(false))
    }

    for {
      apis <- fetchApis
      subscriptions <- fetchSubscriptions(applicationId)
    } yield apis.map(api => APISubscription.from(api, subscriptions))
  }

  def isSubscribed(applicationId: UUID, api: APIIdentifier): Future[Boolean] = {
    subscriptionRepository.isSubscribed(applicationId, api)
  }

  def createSubscriptionForApplication(applicationId: UUID, apiIdentifier: APIIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    val versionSubscriptionFuture: Future[Option[VersionSubscription]] = fetchAllSubscriptionsForApplication(applicationId) map { apis =>
      apis.find(_.context == apiIdentifier.context) flatMap (_.versions.find(_.version.version == apiIdentifier.version))
    }

    val fetchAppFuture = fetchApp(applicationId)

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
      _ <- wso2APIStore.addSubscription(app.wso2Username, app.wso2Password, app.wso2ApplicationName, apiIdentifier, app.rateLimitTier) map { _ =>
        auditSubscription(Subscribed, app, apiIdentifier)
      }
      _ <- subscriptionRepository.add(applicationId, apiIdentifier)
    } yield HasSucceeded
  }

  def removeSubscriptionForApplication(applicationId: UUID, apiIdentifier: APIIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] =
    for {
      app <- fetchApp(applicationId)
      _ <- wso2APIStore.removeSubscription(app.wso2Username, app.wso2Password, app.wso2ApplicationName, apiIdentifier) map { _ =>
        auditSubscription(Unsubscribed, app, apiIdentifier)
      }
      _ <- subscriptionRepository.remove(applicationId, apiIdentifier)
    } yield HasSucceeded

  private def fetchSubscriptions(applicationId: UUID)(implicit hc: HeaderCarrier): Future[Seq[APIIdentifier]] = fetchApp(applicationId) flatMap { app =>
    wso2APIStore.getSubscriptions(app.wso2Username, app.wso2Password, app.wso2ApplicationName)
  }

  private def auditSubscription(action: AuditAction, app: ApplicationData, api: APIIdentifier)(implicit hc: HeaderCarrier): Unit = {
    auditService.audit(action, Map(
      "applicationId" -> app.id.toString,
      "apiVersion" -> api.version,
      "apiContext" -> api.context
    ))
  }

  private def fetchApp(applicationId: UUID) = {
    val notFoundException = new NotFoundException(s"Application not found for id: $applicationId")
    applicationRepository.fetch(applicationId).flatMap {
      case Some(app) => successful(app)
      case _ => failed(notFoundException)
    }
  }

  def refreshSubscriptions()(implicit hc: HeaderCarrier): Future[Int] = {
    def updateSubscriptions(app: ApplicationData, subscriptionsToAdd: Seq[APIIdentifier], subscriptionsToRemove: Seq[APIIdentifier]): Future[Int] = {
      val addSubscriptions = sequence(subscriptionsToAdd.map { sub =>
        Logger.warn(s"Inconsistency in subscription collection. Adding subscription in Mongo. appId=${app.id} context=${sub.context} version=${sub.version}")
        subscriptionRepository.add(app.id, sub)
      })
      val removeSubscriptions = sequence(subscriptionsToRemove.map { sub =>
        Logger.warn(s"Inconsistency in subscription collection. Removing subscription in Mongo. appId=${app.id} context=${sub.context} version=${sub.version}")
        subscriptionRepository.remove(app.id, sub)
      })

      for {
        added <- addSubscriptions
        removed <- removeSubscriptions
      } yield added.size + removed.size
    }

    //Processing applications 1 by 1 as WSO2 times out when too many subscriptions calls are made simultaneously
    def processApplicationsOneByOne(apps: Seq[ApplicationData], total: Int = 0)(implicit hc: HeaderCarrier): Future[Int] = {
      apps match {
        case app :: tail => processApplication(app) flatMap (modified => processApplicationsOneByOne(tail, modified + total))
        case Nil => successful(total)
      }
    }

    def processApplication(app: ApplicationData)(implicit hc: HeaderCarrier): Future[Int] = {

      val mongoSubscriptionsFuture = subscriptionRepository.getSubscriptions(app.id)

      for {
        mongoSubscriptions <- mongoSubscriptionsFuture
        wso2Subscriptions <- wso2APIStore.getSubscriptions(app.wso2Username, app.wso2Password, app.wso2ApplicationName)
        subscriptionsToAdd = wso2Subscriptions.filterNot(mongoSubscriptions.contains(_))
        subscriptionsToRemove = mongoSubscriptions.filterNot(wso2Subscriptions.contains(_))
        sub <- updateSubscriptions(app, subscriptionsToAdd, subscriptionsToRemove)
      } yield sub
    }

    for {
      apps <- applicationRepository.findAll()
      _ = Logger.info(s"Found ${apps.length} applications for subscriptions refresh.")
      subs <- processApplicationsOneByOne(apps)
      } yield subs
  }
}
