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

import cats.Foldable.ops.toAllFoldableOps
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.{Actor, CollaboratorActor, GatekeeperUserActor}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.HeaderCarrierHelper

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.Future.{failed, successful}

@Singleton
class SubscriptionService @Inject() (
    applicationRepository: ApplicationRepository,
    subscriptionRepository: SubscriptionRepository,
    auditService: AuditService,
    apiPlatformEventService: ApiPlatformEventService,
    applicationUpdateService: ApplicationUpdateService,
    apiGatewayStore: ApiGatewayStore
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger {

  val IgnoredContexts: List[String] = List("sso-in/sso", "web-session/sso-api")

  def searchCollaborators(context: ApiContext, version: ApiVersion, partialEmailMatch: Option[String]): Future[List[String]] = {
    subscriptionRepository.searchCollaborators(context, version, partialEmailMatch)
  }

  def fetchAllSubscriptions(): Future[List[SubscriptionData]] = subscriptionRepository.findAll

  def fetchAllSubscriptionsForApplication(applicationId: ApplicationId): Future[Set[ApiIdentifier]] = {
    for {
      _             <- fetchApp(applicationId) // Determine whether application exists and fail if it doesn't
      subscriptions <- subscriptionRepository.getSubscriptions(applicationId)
    } yield subscriptions.toSet
  }

  def isSubscribed(applicationId: ApplicationId, api: ApiIdentifier): Future[Boolean] = {
    subscriptionRepository.isSubscribed(applicationId, api)
  }

  def updateApplicationForApiSubscription(
      applicationId: ApplicationId,
      applicationName: String,
      collaborators: Set[Collaborator],
      api: ApiIdentifier
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    val actor          = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), collaborators)
    val subscribeToApi = SubscribeToApi(actor, api, LocalDateTime.now())
    applicationUpdateService.update(applicationId, subscribeToApi).value.map {
      case Left(e)  =>
        logger.warn(s"Command Process failed for $applicationId because ${e.toList.mkString("[", ",", "]")}")
        throw FailedToSubscribeException(applicationName, api)
      case Right(_) => HasSucceeded
    }
  }

  @deprecated("remove when no longer using old logic")
  def createSubscriptionForApplicationMinusChecks(applicationId: ApplicationId, apiIdentifier: ApiIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    for {
      app <- fetchApp(applicationId)
      _   <- subscriptionRepository.add(applicationId, apiIdentifier)
      _   <- apiPlatformEventService.sendApiSubscribedEvent(app, apiIdentifier.context, apiIdentifier.version)
      _   <- auditSubscription(Subscribed, applicationId, apiIdentifier)
    } yield HasSucceeded
  }

  @deprecated("remove when no longer using old logic")
  def removeSubscriptionForApplication(applicationId: ApplicationId, apiIdentifier: ApiIdentifier)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    for {
      app <- fetchApp(applicationId)
      _   <- subscriptionRepository.remove(applicationId, apiIdentifier)
      _   <- apiPlatformEventService.sendApiUnsubscribedEvent(app, apiIdentifier.context, apiIdentifier.version)
      _   <- auditSubscription(Unsubscribed, applicationId, apiIdentifier)
    } yield HasSucceeded
  }

  @deprecated("remove when no longer using old logic")
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

  private def getActorFromContext(userContext: Map[String, String], collaborators: Set[Collaborator]): Actor =
    userContext.get(HeaderCarrierHelper.DEVELOPER_EMAIL_KEY)
      .map(email => deriveActor(email, collaborators))
      .getOrElse(GatekeeperUserActor("Gatekeeper Admin"))

  private def deriveActor(userEmail: String, collaborators: Set[Collaborator]): Actor =
    collaborators.find(_.emailAddress.equalsIgnoreCase(userEmail)) match {
      case None                  => GatekeeperUserActor("Gatekeeper Admin")
      case Some(_: Collaborator) => CollaboratorActor(userEmail)
    }

}
