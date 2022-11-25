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

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.ApplicationDeleted

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.connector.ThirdPartyDelegatedAuthorityConnector

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import cats.data.NonEmptyList

@Singleton
class ThirdPartyDelegatedAuthorityService @Inject() (
    thirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector
  ) (implicit val ec: ExecutionContext) extends EitherTHelper[String] {

  import cats.instances.future.catsStdInstancesForFuture

  def revokeApplicationAuthorities(event: ApplicationDeleted)(implicit hc: HeaderCarrier): Future[Option[HasSucceeded]] = {
    (
      for {
        result <- liftF(thirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(event.clientId))
      } yield result
    )
      .toOption
      .value
  }

  def applyEvents(events: NonEmptyList[UpdateApplicationEvent])(implicit hc: HeaderCarrier): Future[Option[HasSucceeded]] = {
    events match {
      case NonEmptyList(e, Nil)  => applyEvent(e)
      case NonEmptyList(e, tail) => applyEvent(e).flatMap(_ => applyEvents(NonEmptyList.fromListUnsafe(tail)))
    }
  }

  private def applyEvent(event: UpdateApplicationEvent)(implicit hc: HeaderCarrier): Future[Option[HasSucceeded]] = {
    event match {
      case evt : ApplicationDeleted => revokeApplicationAuthorities(evt)
      case _ => Future.successful(None)
    }
  }
}
