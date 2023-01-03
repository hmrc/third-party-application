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

import uk.gov.hmrc.apiplatform.modules.submissions.SubmissionsTestData
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global

class DeleteApplicationByGatekeeperCommandHandlerSpec extends AsyncHmrcSpec with ApplicationTestData with SubmissionsTestData {

  trait Setup {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val appId = ApplicationId.random
    val requestedByEmail = "admin@example.com"
    val gatekeeperUser = "gatekeeperuser"
    val actor = GatekeeperUserActor(gatekeeperUser)
    val reasons = "reasons description text"
    val app = anApplicationData(appId, environment = Environment.SANDBOX)
    val ts = LocalDateTime.now
    val underTest = new DeleteApplicationByGatekeeperCommandHandler
  }

  "process" should {
    "create correct event for a valid request with a standard app" in new Setup {
      
      val result = await(underTest.process(app, DeleteApplicationByGatekeeper(gatekeeperUser, requestedByEmail, reasons, ts)))
      
      result.isValid shouldBe true
      result.toOption.get.length shouldBe 2

      val applicationDeleted = result.toOption.get.head.asInstanceOf[ApplicationDeletedByGatekeeper]
      applicationDeleted.applicationId shouldBe appId
      applicationDeleted.eventDateTime shouldBe ts
      applicationDeleted.actor shouldBe actor
      applicationDeleted.reasons shouldBe reasons
      applicationDeleted.clientId shouldBe app.tokens.production.clientId
      applicationDeleted.wso2ApplicationName shouldBe app.wso2ApplicationName
      applicationDeleted.requestingAdminEmail shouldBe requestedByEmail

      val stateEvent = result.toOption.get.tail.head.asInstanceOf[ApplicationStateChanged]
      stateEvent.applicationId shouldBe appId
      stateEvent.eventDateTime shouldBe ts
      stateEvent.actor shouldBe actor
      stateEvent.newAppState shouldBe State.DELETED
      stateEvent.oldAppState shouldBe app.state.name
    }
  }
}
