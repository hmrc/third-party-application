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

package uk.gov.hmrc.apiplatform.modules.upliftlinks.repositories

import org.scalatest.BeforeAndAfterEach
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.apiplatform.modules.upliftlinks.domain.models.UpliftLink
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.utils.ServerBaseISpec

import java.time.Clock

class UpliftLinksRepositoryISpec
    extends ServerBaseISpec
    with BeforeAndAfterEach
    with FixedClock {

  protected override def appBuilder: GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure(
        "metrics.jvm" -> false,
        "mongodb.uri" -> s"mongodb://localhost:27017/test-${this.getClass.getSimpleName}"
      )
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }

  private val repository = app.injector.instanceOf[UpliftLinksRepository]

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repository.collection.drop().toFuture())

    await(repository.ensureIndexes)
  }

  trait Setup {
    val sandboxApplicationId    = ApplicationId.random
    val productionApplicationId = ApplicationId.random
    val upliftLink              = UpliftLink(sandboxApplicationId, productionApplicationId)
  }

  "insert" should {

    "save an uplift link so it can be found using the productionApplicationId" in new Setup {
      await(repository.insert(upliftLink))

      val resultFind = await(repository.find(productionAppId = productionApplicationId))

      resultFind mustBe Some(sandboxApplicationId)
    }
  }

}
