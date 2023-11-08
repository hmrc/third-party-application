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

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.{HeaderCarrier, NotFoundException}

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.Collaborator
import uk.gov.hmrc.apiplatform.modules.commands.applications.domain.models.ApplicationCommands
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actors, _}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.util.{ActorHelper, HeaderCarrierHelper}

@Singleton
class SubscriptionService @Inject() (
    applicationRepository: ApplicationRepository,
    subscriptionRepository: SubscriptionRepository,
    applicationCommandDispatcher: ApplicationCommandDispatcher
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with ActorHelper {

  def searchCollaborators(context: ApiContext, version: ApiVersionNbr, partialEmailMatch: Option[String]): Future[List[String]] = {
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
    val actor          = getActorFromContext(HeaderCarrierHelper.headersToUserContext(hc), collaborators).getOrElse(Actors.Unknown)
    val subscribeToApi = ApplicationCommands.SubscribeToApi(actor, api, LocalDateTime.now())
    applicationCommandDispatcher.dispatch(applicationId, subscribeToApi, Set.empty).value.map {
      case Left(e)  =>
        logger.warn(s"Command Process failed for $applicationId because ${e.toList.mkString("[", ",", "]")}")
        throw FailedToSubscribeException(applicationName, api)
      case Right(_) => HasSucceeded
    }
  }

  private def fetchApp(applicationId: ApplicationId) = {
    applicationRepository.fetch(applicationId).flatMap {
      case Some(app) => successful(app)
      case _         => failed(new NotFoundException(s"Application not found for id: ${applicationId.value}"))
    }
  }
}
