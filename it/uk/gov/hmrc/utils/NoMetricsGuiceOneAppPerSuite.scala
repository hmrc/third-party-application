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

package uk.gov.hmrc.utils

import java.time.Clock

import org.scalatest.TestSuite
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.thirdpartyapplication.config.SchedulerModule

trait NoMetricsGuiceOneAppPerSuite extends GuiceOneAppPerSuite with FixedClock {
  self: TestSuite =>

  final override def fakeApplication(): Application =
    builder().build()

  def builder(): GuiceApplicationBuilder = {
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .overrides(bind[Clock].toInstance(clock))
      .disable(classOf[SchedulerModule])
  }
}
