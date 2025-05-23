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

package uk.gov.hmrc.thirdpartyapplication.controllers

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.apiplatform.modules.apis.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util._

trait ControllerTestData extends ApplicationWithCollaboratorsFixtures with CollaboratorTestData with FixedClock {

  val collaborators: Set[Collaborator] = standardApp.collaborators

  def anAPI() = {
    "some-context".asIdentifier("1.0")
  }

  def aSubcriptionData() = {
    SubscriptionData(anAPI(), Set(ApplicationId.random, ApplicationId.random))
  }

  def anAPIJson() = {
    """{ "context" : "some-context", "version" : "1.0" }"""
  }
}
