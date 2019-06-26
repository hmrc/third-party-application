/*
 * Copyright 2019 HM Revenue & Customs
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
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.{AwsApiGatewayConnector, UpsertApplicationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.BRONZE
import uk.gov.hmrc.thirdpartyapplication.models.{ApplicationData, HasSucceeded, Wso2Api}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}

import scala.concurrent.Future.sequence
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AwsRestoreService @Inject()(awsApiGatewayConnector: AwsApiGatewayConnector,
                                  applicationRepository: ApplicationRepository,
                                  subscriptionRepository: SubscriptionRepository) {

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  def restoreData()(implicit hc: HeaderCarrier): Future[Seq[HasSucceeded]] = {
    Logger.info("Republishing all Applications to AWS API Gateway")

    applicationRepository.fetchAll()
      .flatMap(applications => sequence(applications.map(restoreApplication)))
  }

  def restoreApplication(application: ApplicationData)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Logger.debug(s"Republishing Application [${application.wso2ApplicationName}]")

    for {
      apiIdentifiers <- subscriptionRepository.getSubscriptions(application.id)
      apiNames = apiIdentifiers.map(api => Wso2Api.create(api).name)
      request = UpsertApplicationRequest(application.rateLimitTier.getOrElse(BRONZE), application.tokens.production.accessToken, apiNames)
      result <- awsApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, request)(hc)
    } yield result
  }
}
