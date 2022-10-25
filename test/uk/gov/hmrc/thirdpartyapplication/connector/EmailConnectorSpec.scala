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

package uk.gov.hmrc.thirdpartyapplication.connector

import play.api.http.Status._
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient}
import com.github.tomakehurst.wiremock.client.WireMock._

import scala.concurrent.ExecutionContext.Implicits.global
import EmailConnector.SendEmailRequest
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationId, Role}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded

class EmailConnectorSpec extends ConnectorSpec {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val hubTestTitle       = "Unit Test Hub Title"
  private val hubUrl             = "http://localhost:9685"
  private val environmentName    = "sandbox"

  trait Setup {
    val http: HttpClient = app.injector.instanceOf[HttpClient]
    val config           = EmailConnector.Config(wireMockUrl, hubUrl, hubTestTitle, environmentName)
    val connector        = new EmailConnector(http, config)

    wireMockServer.resetRequests()

    final def emailWillReturn(request: SendEmailRequest) = {
      stubFor(
        post(urlPathEqualTo("/hmrc/email"))
          .withJsonRequestBody(request)
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )
    }

    final def verifySent() = {
      wireMockServer.verify(
        postRequestedFor(urlEqualTo("/hmrc/email"))
      )
    }
  }

  "emailConnector" should {
    val collaboratorEmail = "email@example.com"
    val adminEmail1       = "admin1@example.com"
    val adminEmail2       = "admin2@example.com"
    val gatekeeperEmail   = "gatekeeper@example.com"
    val role              = "admin"
    val applicationName   = "Test Application"
    val applicationId     = ApplicationId.random

    "send added collaborator confirmation email" in new Setup {
      val role                                    = "admin"
      val expectedTemplateId                      = "apiAddedDeveloperAsCollaboratorConfirmation"
      val expectedToEmails                        = Set(collaboratorEmail)
      val expectedParameters: Map[String, String] = Map(
        "article"           -> "an",
        "role"              -> role,
        "applicationName"   -> applicationName,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendAddedCollaboratorConfirmation(Role.ADMINISTRATOR, applicationName, expectedToEmails))
    }

    "send added collaborator confirmation email with article for developer" in new Setup {
      val role                                    = "developer"
      val expectedTemplateId                      = "apiAddedDeveloperAsCollaboratorConfirmation"
      val expectedToEmails                        = Set(collaboratorEmail)
      val expectedParameters: Map[String, String] = Map(
        "article"           -> "a",
        "role"              -> role,
        "applicationName"   -> applicationName,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendAddedCollaboratorConfirmation(Role.DEVELOPER, applicationName, expectedToEmails))
    }

    "send added collaborator notification email" in new Setup {

      val expectedTemplateId                      = "apiAddedDeveloperAsCollaboratorNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "email"             -> collaboratorEmail,
        "role"              -> role,
        "applicationName"   -> applicationName,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendCollaboratorAddedNotification(collaboratorEmail, Role.ADMINISTRATOR, applicationName, expectedToEmails))
    }

    "send removed collaborator confirmation email" in new Setup {

      val expectedTemplateId                      = "apiRemovedCollaboratorConfirmation"
      val expectedToEmails                        = Set(collaboratorEmail)
      val expectedParameters: Map[String, String] = Map(
        "applicationName"   -> applicationName,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendRemovedCollaboratorConfirmation(applicationName, Set(collaboratorEmail)))
    }

    "send removed collaborator notification email" in new Setup {

      val expectedTemplateId                      = "apiRemovedCollaboratorNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "email"             -> collaboratorEmail,
        "applicationName"   -> applicationName,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendRemovedCollaboratorNotification(collaboratorEmail, applicationName, expectedToEmails))
    }

    "send application approved gatekeeper confirmation email" in new Setup {

      val expectedTemplateId                      = "apiApplicationApprovedGatekeeperConfirmation"
      val expectedToEmails                        = Set(gatekeeperEmail)
      val expectedParameters: Map[String, String] = Map(
        "email"           -> adminEmail1,
        "applicationName" -> applicationName
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendApplicationApprovedGatekeeperConfirmation(adminEmail1, applicationName, expectedToEmails))
    }

    "send application approved admin confirmation email" in new Setup {

      val code                                    = "generatedCode"
      val expectedTemplateId                      = "apiApplicationApprovedAdminConfirmation"
      val expectedToEmails                        = Set(adminEmail1)
      val expectedParameters: Map[String, String] = Map(
        "applicationName"  -> applicationName,
        "developerHubLink" -> s"${connector.devHubBaseUrl}/developer/application-verification?code=$code"
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendApplicationApprovedAdminConfirmation(applicationName, code, expectedToEmails))
    }

    "send application approved notification email" in new Setup {

      val expectedTemplateId                      = "apiApplicationApprovedNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> applicationName
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendApplicationApprovedNotification(applicationName, Set(adminEmail1, adminEmail2)))
    }

    "send application rejected notification email" in new Setup {

      val reason                                  = "Test Error"
      val expectedTemplateId                      = "apiApplicationRejectedNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> applicationName,
        "guidelinesUrl"   -> s"${connector.devHubBaseUrl}/api-documentation/docs/using-the-hub/name-guidelines",
        "supportUrl"      -> s"${connector.devHubBaseUrl}/developer/support",
        "reason"          -> s"$reason"
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendApplicationRejectedNotification(applicationName, expectedToEmails, reason))
    }

    "send application deleted notification email" in new Setup {

      val expectedTemplateId                      = "apiApplicationDeletedNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> applicationName,
        "requestor"       -> s"$adminEmail1",
        "applicationId"   -> applicationId.value.toString()
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendApplicationDeletedNotification(applicationName, applicationId, adminEmail1, expectedToEmails))
    }

    "send added client secret notification email" in new Setup {
      val expectedTemplateId                      = "apiAddedClientSecretNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val clientSecretName: String                = "***cret"
      val expectedParameters: Map[String, String] = Map(
        "actorEmailAddress"  -> adminEmail1,
        "clientSecretEnding" -> "cret",
        "applicationName"    -> applicationName,
        "environmentName"    -> environmentName,
        "developerHubTitle"  -> hubTestTitle
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendAddedClientSecretNotification(adminEmail1, clientSecretName, applicationName, expectedToEmails))
    }

    "send removed client secret notification email" in new Setup {
      val expectedTemplateId                      = "apiRemovedClientSecretNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val clientSecretName: String                = "***cret"
      val expectedParameters: Map[String, String] = Map(
        "actorEmailAddress"  -> adminEmail1,
        "clientSecretEnding" -> "cret",
        "applicationName"    -> applicationName,
        "environmentName"    -> environmentName,
        "developerHubTitle"  -> hubTestTitle
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendRemovedClientSecretNotification(adminEmail1, clientSecretName, applicationName, expectedToEmails))
    }

    "send verify responsible individual notification email" in new Setup {
      val responsibleIndividualName               = "Bob Example"
      val responsibleIndividualEmail              = "bob@example.com"
      val adminName                               = "John Admin"
      val appName                                 = "my app"
      val uniqueId                                = "abc123"
      val expectedParameters: Map[String, String] = Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> appName,
        "requesterName"             -> adminName,
        "developerHubLink"          -> s"$hubUrl/developer/submissions/responsible-individual-verification?code=$uniqueId"
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(Set(responsibleIndividualEmail), "apiVerifyResponsibleIndividual", expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendVerifyResponsibleIndividualNotification(responsibleIndividualName, responsibleIndividualEmail, appName, adminName, uniqueId))
    }

    "send verify Responsible Individual reminder to admin" in new Setup {
      val responsibleIndividualName               = "Bob Example"
      val adminEmail                              = "admin@example.com"
      val adminName                               = "John Admin"
      val appName                                 = "my app"
      val expectedParameters: Map[String, String] = Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> appName,
        "requesterName"             -> adminName
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(Set(adminEmail), "apiResponsibleIndividualReminderToAdmin", expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendVerifyResponsibleIndividualReminderToAdmin(responsibleIndividualName, adminEmail, appName, adminName))
    }

    "send responsible individual did not verify" in new Setup {
      val responsibleIndividualName               = "Bob Example"
      val adminEmail                              = "admin@example.com"
      val adminName                               = "John Admin"
      val appName                                 = "my app"
      val expectedParameters: Map[String, String] = Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> appName,
        "requesterName"             -> adminName
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(Set(adminEmail), "apiResponsibleIndividualDidNotVerify", expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendResponsibleIndividualDidNotVerify(responsibleIndividualName, adminEmail, appName, adminName))
    }

    "send change of application name" in new Setup {
      val requesterName                           = "bob@example.com"
      val previousAppName                         = "Previous Application Name"
      val newAppName                              = "New App Name"
      val expectedParameters: Map[String, String] = Map(
        "requesterName"           -> requesterName,
        "previousApplicationName" -> previousAppName,
        "newApplicationName"      -> newAppName
      )
      val recipients                              = Set("admin@example.com", "dev@example.com", "ri@example.com")
      val expectedRequest: SendEmailRequest       = SendEmailRequest(recipients, "apiChangeOfApplicationName", expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendChangeOfApplicationName(requesterName, previousAppName, newAppName, recipients))
    }

    "send change of application details" in new Setup {
      val requesterName                           = "bob@example.com"
      val applicationName                         = "App name"
      val fieldName                               = "privacy policy URL"
      val previousValue                           = "https://example.com/previous-privacy-policy"
      val newValue                                = "https://example.com/new-privacy-policy"
      val expectedParameters: Map[String, String] = Map(
        "requesterName"   -> requesterName,
        "applicationName" -> applicationName,
        "fieldName"       -> fieldName,
        "previousValue"   -> previousValue,
        "newValue"        -> newValue
      )
      val recipients                              = Set("admin@example.com", "dev@example.com", "ri@example.com")
      val expectedRequest: SendEmailRequest       = SendEmailRequest(recipients, "apiChangeOfApplicationDetails", expectedParameters)
      
      emailWillReturn(expectedRequest)

      val result = await(connector.sendChangeOfApplicationDetails(requesterName, applicationName, fieldName, previousValue, newValue, recipients))

      result shouldBe HasSucceeded
      verifySent
    }

    "send verify responsible individual update notification" in new Setup {
      val responsibleIndividualName  = "Bob Example"
      val responsibleIndividualEmail = "bob@example.com"
      val adminName                  = "John Admin"
      val appName                    = "app name"
      val verificationId             = ResponsibleIndividualVerificationId.random.value

      val expectedParameters: Map[String, String] = Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName" -> appName,
        "requesterName" -> adminName,
        "developerHubLink" -> s"$hubUrl/developer/submissions/responsible-individual-verification?code=$verificationId"
      )
      val recipients                        = Set(responsibleIndividualEmail)
      val expectedRequest: SendEmailRequest = SendEmailRequest(recipients, "apiVerifyResponsibleIndividualUpdate", expectedParameters)

      emailWillReturn(expectedRequest)

      val result = await(connector.sendVerifyResponsibleIndividualUpdateNotification(responsibleIndividualName, responsibleIndividualEmail, appName, adminName, verificationId))

      result shouldBe HasSucceeded
      verifySent
    }
  }
}
