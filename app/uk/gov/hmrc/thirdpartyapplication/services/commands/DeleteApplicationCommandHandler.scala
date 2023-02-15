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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import java.time.LocalDateTime

import cats.data.NonEmptyList

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.approvals.repositories.ResponsibleIndividualVerificationRepository
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository._
import uk.gov.hmrc.thirdpartyapplication.services._
import uk.gov.hmrc.apiplatform.modules.events.applications.domain.models.AbstractApplicationEvent
import uk.gov.hmrc.thirdpartyapplication.domain.models.StateHistory

trait DeleteApplicationCommandHandler extends CommandHandler {
  val applicationRepository: ApplicationRepository
  val apiGatewayStore: ApiGatewayStore
  val notificationRepository: NotificationRepository
  val responsibleIndividualVerificationRepository: ResponsibleIndividualVerificationRepository
  val thirdPartyDelegatedAuthorityService: ThirdPartyDelegatedAuthorityService
  val stateHistoryRepository: StateHistoryRepository

  def deleteApplication(
      app: ApplicationData,
      stateHistory: StateHistory,
      timestamp: LocalDateTime,
      requestingAdminEmail: String,
      requestingAdminName: String,
      events: NonEmptyList[AbstractApplicationEvent]
    )(implicit hc: HeaderCarrier
    ) = {
    for {
      _ <- E.liftF(stateHistoryRepository.insert(stateHistory))
      _ <- E.liftF(thirdPartyDelegatedAuthorityService.applyEvents(events))
      _ <- E.liftF(responsibleIndividualVerificationRepository.applyEvents(events))
      _ <- E.liftF(apiGatewayStore.applyEvents(events))
      _ <- E.liftF(notificationRepository.applyEvents(events))
    } yield HasSucceeded
  }
}
