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

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.Future.{failed, successful}

@Singleton
class SubscriptionService @Inject() (
    applicationRepository: ApplicationRepository,
    subscriptionRepository: SubscriptionRepository,
    auditService: AuditService,
    apiPlatformEventService: ApiPlatformEventService,
    apiGatewayStore: ApiGatewayStore
  )(implicit val ec: ExecutionContext
  ) {

  val IgnoredContexts: List[String] = List("sso-in/sso", "web-session/sso-api")

  def searchCollaborators(context: ApiContext, version: ApiVersion, partialEmailMatch: Option[String]): Future[List[String]] = {
    subscriptionRepository.searchCollaborators(context, version, partialEmailMatch)
  }

  def fetchAllSubscriptions(): Future[List[SubscriptionData]] = subscriptionRepository.findAll()

  def fetchAllSubscriptionsForApplication(applicationId: ApplicationId): Future[Set[ApiIdentifier]] = {
    for {
      _             <- fetchApp(applicationId) // Determine whether application exists and fail if it doesn't
      subscriptions <- subscriptionRepository.getSubscriptions(applicationId)
    } yield subscriptions.toSet
  }

  def isSubscribed(applicationId: ApplicationId, api: ApiIdentifier): Future[Boolean] = {
    subscriptionRepository.isSubscribed(applicationId, api)
  }

  def createSubscriptionForApplicationMinusChecks(applicationId: ApplicationId, apiIdentifier: ApiIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    for {
      app <- fetchApp(applicationId)
      _   <- subscriptionRepository.add(applicationId, apiIdentifier)
      _   <- apiPlatformEventService.sendApiSubscribedEvent(app, apiIdentifier.context, apiIdentifier.version)
      _   <- auditSubscription(Subscribed, applicationId, apiIdentifier)
    } yield HasSucceeded
  }

  def removeSubscriptionForApplication(applicationId: ApplicationId, apiIdentifier: ApiIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    for {
      app <- fetchApp(applicationId)
      _   <- subscriptionRepository.remove(applicationId, apiIdentifier)
      _   <- apiPlatformEventService.sendApiUnsubscribedEvent(app, apiIdentifier.context, apiIdentifier.version)
      _   <- auditSubscription(Unsubscribed, applicationId, apiIdentifier)
    } yield HasSucceeded
  }

  private def auditSubscription(action: AuditAction, applicationId: ApplicationId, api: ApiIdentifier)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    auditService.audit(
      action,
      Map(
        "applicationId" -> applicationId.value.toString,
        "apiVersion"    -> api.version.value,
        "apiContext"    -> api.context.value
      )
    )
  }

  private def fetchApp(applicationId: ApplicationId) = {
    applicationRepository.fetch(applicationId).flatMap {
      case Some(app) => successful(app)
      case _         => failed(new NotFoundException(s"Application not found for id: ${applicationId.value}"))
    }
  }

}
