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

import cats.data.OptionT
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import uk.gov.hmrc.apiplatform.modules.upliftlinks.repositories.UpliftLinksRepository
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpliftLinkService @Inject() (repo: UpliftLinksRepository)(implicit ec: ExecutionContext) {

  def createUpliftLink(sandboxApplicationId: ApplicationId, productionApplicationId: ApplicationId): Future[UpliftLink] =
    repo.insert(UpliftLink(sandboxApplicationId, productionApplicationId))

  def getSandboxAppForProductionAppId(productionAppId: ApplicationId): OptionT[Future, ApplicationId] =
    OptionT(repo.find(productionAppId))

}
