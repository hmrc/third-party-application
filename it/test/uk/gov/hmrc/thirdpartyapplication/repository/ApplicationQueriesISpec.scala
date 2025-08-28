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

package uk.gov.hmrc.thirdpartyapplication.repository

import org.scalatest.BeforeAndAfterEach

import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.utils.ServerBaseISpec

import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.thirdpartyapplication.util._

class ApplicationQueriesISpec
    extends ServerBaseISpec
    with CleanMongoCollectionSupport
    with SubmissionsTestData
    with StoredApplicationFixtures
    with JavaDateTimeTestUtils
    with BeforeAndAfterEach
    with MetricsHelper
    with FixedClock
    with ApplicationRepositoryTestData {}
