/*
 * Copyright 2019 HM Revenue & Customs
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

package unit.uk.gov.hmrc.thirdpartyapplication.connector

import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.ScalaFutures
import play.api.http.Status._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.connector._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EmailConnectorSpec extends UnitSpec with MockitoSugar with ArgumentMatchersSugar with ScalaFutures {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val baseUrl = s"http://example.com"
  private val hubTestTitle = "Unit Test Hub Title"
  private val hubUrl = "http://localhost:9685"
  private val hubLink = s"$hubUrl/developer/registration"

  trait Setup {
    val mockHttpClient = mock[HttpClient]
    val config = EmailConfig(baseUrl, hubUrl, hubTestTitle)
    val connector = new EmailConnector(mockHttpClient, config)

    def emailWillReturn(result: Future[HttpResponse]) = {
      when(mockHttpClient.POST[SendEmailRequest, HttpResponse](*, *, *)(*, *, *, *)).thenReturn(result)
    }

    def verifyEmailCalled(request: SendEmailRequest) = {
      val expectedUrl = s"${config.baseUrl}/hmrc/email"
      verify(mockHttpClient).POST[SendEmailRequest, HttpResponse](eqTo(expectedUrl), eqTo(request), *)(*, *, *, *)
    }
  }

  "emailConnector" should {
    val collaboratorEmail = "email@example.com"
    val adminEmail1 = "admin1@example.com"
    val adminEmail2 = "admin2@example.com"
    val gatekeeperEmail = "gatekeeper@example.com"
    val role = "admin"
    val application = "Test Application"

    "send added collaborator confirmation email" in new Setup {
      val expectedTemplateId = "apiAddedDeveloperAsCollaboratorConfirmation"
      val expectedToEmails = Set(collaboratorEmail)
      val expectedParameters: Map[String, String] = Map(
        "role" -> role,
        "applicationName" -> application,
        "developerHubLink" -> hubLink,
        "developerHubTitle" -> hubTestTitle
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendAddedCollaboratorConfirmation(role, application, expectedToEmails))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }

    "send added collaborator notification email" in new Setup {

      val expectedTemplateId = "apiAddedDeveloperAsCollaboratorNotification"
      val expectedToEmails = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "email" -> collaboratorEmail,
        "role" -> role,
        "applicationName" -> application,
        "developerHubTitle" -> hubTestTitle
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendAddedCollaboratorNotification(collaboratorEmail, role, application, expectedToEmails))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }

    "send removed collaborator confirmation email" in new Setup {

      val expectedTemplateId = "apiRemovedCollaboratorConfirmation"
      val expectedToEmails = Set(collaboratorEmail)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> application,
        "developerHubTitle" -> hubTestTitle
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendRemovedCollaboratorConfirmation(application, Set(collaboratorEmail)))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }

    "send removed collaborator notification email" in new Setup {

      val expectedTemplateId = "apiRemovedCollaboratorNotification"
      val expectedToEmails = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "email" -> collaboratorEmail,
        "applicationName" -> application,
        "developerHubTitle" -> hubTestTitle
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendRemovedCollaboratorNotification(collaboratorEmail, application, expectedToEmails))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }

    "send application approved gatekeeper confirmation email" in new Setup {

      val expectedTemplateId = "apiApplicationApprovedGatekeeperConfirmation"
      val expectedToEmails = Set(gatekeeperEmail)
      val expectedParameters: Map[String, String] = Map(
        "email" -> adminEmail1,
        "applicationName" -> application
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendApplicationApprovedGatekeeperConfirmation(adminEmail1, application, expectedToEmails))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)

    }

    "send application approved admin confirmation email" in new Setup {

      val code = "generatedCode"
      val expectedTemplateId = "apiApplicationApprovedAdminConfirmation"
      val expectedToEmails = Set(adminEmail1)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> application,
        "developerHubLink" -> s"${connector.devHubBaseUrl}/developer/application-verification?code=$code"
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendApplicationApprovedAdminConfirmation(application, code, expectedToEmails))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }

    "send application approved notification email" in new Setup {

      val expectedTemplateId = "apiApplicationApprovedNotification"
      val expectedToEmails = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> application
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendApplicationApprovedNotification(application, Set(adminEmail1, adminEmail2)))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }

    "send application rejected notification email" in new Setup {

      val reason = "Test Error"
      val expectedTemplateId = "apiApplicationRejectedNotification"
      val expectedToEmails = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> application,
        "guidelinesUrl" -> s"${connector.devHubBaseUrl}/api-documentation/docs/using-the-hub/name-guidelines",
        "supportUrl" -> s"${connector.devHubBaseUrl}/developer/support",
        "reason" -> s"$reason"
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendApplicationRejectedNotification(application, expectedToEmails, reason))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }

    "send application deleted notification email" in new Setup {

      val expectedTemplateId = "apiApplicationDeletedNotification"
      val expectedToEmails = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> application,
        "requestor" -> s"$adminEmail1"
      )

      emailWillReturn(Future(HttpResponse(OK)))

      await(connector.sendApplicationDeletedNotification(application, adminEmail1, expectedToEmails))

      val expectedRequest = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      verifyEmailCalled(expectedRequest)
    }
  }
}
