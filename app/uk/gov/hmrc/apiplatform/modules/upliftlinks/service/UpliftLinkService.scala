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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.service

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.apiplatform.modules.submissions.repositories.UpliftLinksRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import cats.data.OptionT
import cats.implicits._
import uk.gov.hmrc.thirdpartyapplication.services.ApplicationService
import uk.gov.hmrc.thirdpartyapplication.models.ApplicationResponse

@Singleton
class UpliftLinkService @Inject()(upliftLinksRepository: UpliftLinksRepository, applicationService: ApplicationService)(implicit ec: ExecutionContext) {
  def createUpliftLink(sandboxApplicationId: ApplicationId, productionApplicationId: ApplicationId): Future[UpliftLink] = {
    val upliftLink = UpliftLink(sandboxApplicationId, productionApplicationId)
    upliftLinksRepository.insert(upliftLink)
    Future.successful(upliftLink)
  }

  def getSandboxAppForProductionAppId(productionAppId: ApplicationId): OptionT[Future,ApplicationResponse] = {
    for {
      upliftLink <- OptionT(upliftLinksRepository.find("productionApplicationId" -> productionAppId).map(_.headOption))
      sandboxAppId = upliftLink.sandboxApplicationId
      sandboxAppData <- applicationService.fetch(sandboxAppId)
    } yield sandboxAppData    
  }
}