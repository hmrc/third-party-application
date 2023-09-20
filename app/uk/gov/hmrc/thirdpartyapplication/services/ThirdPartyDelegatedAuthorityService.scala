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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ClientId
import uk.gov.hmrc.apiplatform.modules.common.services.EitherTHelper
import uk.gov.hmrc.thirdpartyapplication.connector.ThirdPartyDelegatedAuthorityConnector
import uk.gov.hmrc.thirdpartyapplication.models._

@Singleton
class ThirdPartyDelegatedAuthorityService @Inject() (
    thirdPartyDelegatedAuthorityConnector: ThirdPartyDelegatedAuthorityConnector
  )(implicit val ec: ExecutionContext
  ) extends EitherTHelper[String] {

  import cats.instances.future.catsStdInstancesForFuture

  def revokeApplicationAuthorities(clientId: ClientId)(implicit hc: HeaderCarrier): Future[Option[HasSucceeded]] = {
    (
      for {
        result <- liftF(thirdPartyDelegatedAuthorityConnector.revokeApplicationAuthorities(clientId))
      } yield result
    )
      .toOption
      .value
  }
}
