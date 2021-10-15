/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.submissions.services

import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.thirdpartyapplication.util.EitherTHelper
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import scala.concurrent.Future
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.DeriveContext
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models.Context
import cats.data.EitherT

@Singleton
class ContextService @Inject()(
  applicationRepository: ApplicationRepository,
  subscriptionRepository: SubscriptionRepository
)(implicit val ec: ExecutionContext) extends EitherTHelper[String] {

  import cats.instances.future.catsStdInstancesForFuture

  def deriveContext(applicationId: ApplicationId): EitherT[Future, String, Context] = {
    (
      for {
        application           <- fromOptionF(applicationRepository.fetch(applicationId), "No such application")
        subscriptions         <- liftF(subscriptionRepository.getSubscriptions(applicationId))
        context               =  DeriveContext.deriveFor(application, subscriptions)
      } yield context
    )
  }
}