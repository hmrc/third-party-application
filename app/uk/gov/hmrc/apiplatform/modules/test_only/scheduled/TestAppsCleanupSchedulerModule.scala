/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.test_only.scheduled

import javax.inject.{Inject, Singleton}

import com.google.inject.Provider

import play.api.inject.Module
import play.api.{Configuration, Environment}

import uk.gov.hmrc.apiplatform.modules.scheduling.{ScheduledJobs, ScheduledJobsRunner}
import uk.gov.hmrc.thirdpartyapplication.scheduled.TestApplicationsCleanupJob

class TestAppsCleanupSchedulerModule extends Module {

  override def bindings(environment: Environment, configuration: Configuration) = Seq(
    bind[ScheduledJobs].toProvider[TestAppsCleanupScheduledJobsProvider],
    bind[ScheduledJobsRunner].toSelf.eagerly()
  )
}

@Singleton
class TestAppsCleanupScheduledJobsProvider @Inject() (
    testAppsCleanupJob: TestApplicationsCleanupJob
  ) extends Provider[ScheduledJobs] {
  override def get(): ScheduledJobs = ScheduledJobs(List(testAppsCleanupJob).filter(_.isEnabled))
}
