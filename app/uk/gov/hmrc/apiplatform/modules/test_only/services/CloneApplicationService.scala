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

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}
import uk.gov.hmrc.apiplatform.modules.test_only.repository.TestApplicationsRepository

@Singleton
class CloneApplicationService @Inject() (
    applicationRepository: ApplicationRepository,
    subscriptionRepository: SubscriptionRepository,
    testAppRepo: TestApplicationsRepository
  )(implicit ec: ExecutionContext
  ) {

  private val E = EitherTHelper.make[ApplicationId]

  def cloneApplication(appId: ApplicationId): Future[Either[ApplicationId, StoredApplication]] = {
    val newId = ApplicationId.random
    val newClientId = ClientId.random

    def cloneSubs(): Future[_] = {
      subscriptionRepository.getSubscriptions(appId).flatMap { subsToCopy =>
        Future.sequence(subsToCopy.map(s => subscriptionRepository.add(newId, s)))
      }
    }

    (
      for {
        oldApp           <- E.fromOptionF(applicationRepository.fetch(appId), newId)
        suffix            = Instant.now().toEpochMilli().toHexString
        newName           = ApplicationName(s"${oldApp.name} clone $suffix")
        newNormalisedName = newName.value.toLowerCase
        newApp            = oldApp.copy(id = newId, name = newName, normalisedName = newNormalisedName, tokens = oldApp.tokens.copy(production = oldApp.tokens.production.copy(clientId = newClientId)))
        _            <- E.liftF(testAppRepo.record(newId))
        insertedApp  <- E.liftF(applicationRepository.save(newApp))
        insertedSubs <- E.liftF(cloneSubs())
      } yield insertedApp
    )
      .value
      .recover {
        case NonFatal(_) => Left(newId)
      }
  }
}
