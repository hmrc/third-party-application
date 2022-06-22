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

package uk.gov.hmrc.thirdpartyapplication.services.events

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.NameChangedEmailSent
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global
import java.time.LocalDateTime
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule

class NameChangedNotificationEventHandlerSpec extends AsyncHmrcSpec with ApplicationTestData {

  trait Setup extends EmailConnectorMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val applicationId         = ApplicationId.random
    val devEmail              = "dev@example.com"
    val adminEmail            = "admin@example.com"
    val oldName               = "old app name"
    val newName               = "new app name"
    val responsibleIndividual = ResponsibleIndividual.build("bob example", "bob@example.com")

    val testImportantSubmissionData = ImportantSubmissionData(
      Some("organisationUrl.com"),
      responsibleIndividual,
      Set(ServerLocation.InUK),
      TermsAndConditionsLocation.InDesktopSoftware,
      PrivacyPolicyLocation.InDesktopSoftware,
      List.empty
    )

    val app                  = anApplicationData(applicationId).copy(
      collaborators = Set(
        Collaborator(devEmail, Role.DEVELOPER, idOf(devEmail)),
        Collaborator(adminEmail, Role.ADMINISTRATOR, idOf(adminEmail))
      ),
      name = oldName,
      access = Standard(importantSubmissionData = Some(testImportantSubmissionData))
    )
    val userId               = UserId.random
    val timestamp            = LocalDateTime.now
    val update               = ChangeProductionApplicationName(userId, timestamp, "gkuser", newName)
    val nameChangeEmailEvent = NameChangedEmailSent(applicationId, timestamp, userId, oldName, newName, "admin@example.com")

    val underTest = new NameChangedNotificationEventHandler(EmailConnectorMock.aMock)
  }

  "sendAdviceEmail" should {
    "successfully send email" in new Setup {
      EmailConnectorMock.SendChangeOfApplicationName.thenReturnSuccess()
      val result = await(underTest.sendAdviceEmail(app, nameChangeEmailEvent))
      result shouldBe HasSucceeded
      EmailConnectorMock.SendChangeOfApplicationName.verifyCalledWith(adminEmail, oldName, newName, Set(adminEmail, devEmail, responsibleIndividual.emailAddress.value))
    }
  }
}