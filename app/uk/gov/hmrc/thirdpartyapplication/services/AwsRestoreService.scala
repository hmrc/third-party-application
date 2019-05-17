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

import javax.inject.Inject
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.{AwsApiGatewayConnector, UpsertApplicationRequest}
import uk.gov.hmrc.thirdpartyapplication.models.{APIIdentifier, ApplicationData, HasSucceeded}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, SubscriptionRepository}

import scala.concurrent.{ExecutionContext, Future}

class AwsRestoreService @Inject()(awsApiGatewayConnector: AwsApiGatewayConnector,
                                  applicationRepository: ApplicationRepository,
                                  subscriptionRepository: SubscriptionRepository) {

  implicit val executionContext: ExecutionContext = ExecutionContext.global
  implicit val hc: HeaderCarrier = HeaderCarrier()

  def restoreData(): Future[Seq[HasSucceeded]] = {
    Logger.info("Republishing all Applications to AWS API Gateway")

    applicationRepository.fetchAll()
      .flatMap(applications => Future.sequence(applications.map(restoreApplication)))
  }

  def restoreApplication(application: ApplicationData): Future[HasSucceeded] = {
    Logger.debug(s"Republishing Application [${application.wso2ApplicationName}]")

    val request = UpsertApplicationRequest(application.rateLimitTier.get, application.tokens.production.accessToken)
    awsApiGatewayConnector.createOrUpdateApplication(application.wso2ApplicationName, request)(hc)
      .flatMap(_ => {
        val subscriptions = subscriptionRepository.getSubscriptions(application.id)
        subscriptions
          .flatMap(subs => restoreApplicationSubscriptions(application.wso2ApplicationName, subs))
          .flatMap(_ => Future.successful(HasSucceeded))
      })
  }

  def restoreApplicationSubscriptions(applicationName: String, apis: Seq[APIIdentifier]): Future[Seq[HasSucceeded]] = {
    Future.sequence(
      apis.map(api => {
        val normalisedAPIName = s"${api.context}--${api.version}"

        Logger.debug(s"Resubscribing Application [$applicationName] to API [$normalisedAPIName]")

        awsApiGatewayConnector.addSubscription(applicationName, normalisedAPIName)(hc)
      }))
  }
}
