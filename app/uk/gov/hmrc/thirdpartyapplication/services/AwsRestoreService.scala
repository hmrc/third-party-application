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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AwsRestoreService @Inject() (awsApiGatewayConnector: AwsApiGatewayConnector, applicationRepository: ApplicationRepository) extends ApplicationLogger {

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  val DefaultRateLimitTier: RateLimitTier = BRONZE

  def restoreData()(implicit hc: HeaderCarrier): Future[Unit] = {
    logger.info("Republishing all Applications to AWS API Gateway")

    applicationRepository.processAll(application => {
      logger.debug(s"Republishing Application [${application.wso2ApplicationName}]")
      awsApiGatewayConnector.createOrUpdateApplication(
        application.wso2ApplicationName,
        application.tokens.production.accessToken,
        application.rateLimitTier.getOrElse(DefaultRateLimitTier)
      )(hc)
    })
  }
}
