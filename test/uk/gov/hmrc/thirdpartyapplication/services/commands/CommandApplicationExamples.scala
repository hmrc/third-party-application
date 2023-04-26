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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{PrivacyPolicyLocations, TermsAndConditionsLocations}
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, HasApplicationId}
import uk.gov.hmrc.apiplatform.modules.applications.domain.models.Collaborator

trait CommandApplicationExamples extends HasApplicationId {
  self: ApplicationTestData =>

  val devAndAdminCollaborators: Set[Collaborator] = 
    Set(
      developerCollaborator,
      otherAdminCollaborator
    )

  val principalApp   = anApplicationData(applicationId).copy(
    collaborators = devAndAdminCollaborators
  )
  val subordinateApp = principalApp.copy(environment = Environment.SANDBOX.toString())

  val responsibleIndividual      = ResponsibleIndividual.build("bob example", "bob@example.com")
  val privicyPolicyLocation      = PrivacyPolicyLocations.InDesktopSoftware
  val termsAndConditionsLocation = TermsAndConditionsLocations.InDesktopSoftware

  val testImportantSubmissionData = ImportantSubmissionData(
    Some("organisationUrl.com"),
    responsibleIndividual,
    Set(ServerLocation.InUK),
    termsAndConditionsLocation,
    privicyPolicyLocation,
    List.empty
  )
}
