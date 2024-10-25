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

package uk.gov.hmrc.thirdpartyapplication.connector

import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._

import play.api.http.Status._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.HttpClientV2Support

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.ResponsibleIndividualVerificationId
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector.SendEmailRequest
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.util.CollaboratorTestData

class EmailConnectorSpec extends ConnectorSpec with CollaboratorTestData {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val hubTestTitle       = "Unit Test Hub Title"
  private val hubUrl             = "http://localhost:9685"
  private val environmentName    = "sandbox"

  trait Setup extends HttpClientV2Support {
    val config    = EmailConnector.Config(wireMockUrl, hubUrl, hubTestTitle, environmentName)
    val connector = new EmailConnector(httpClientV2, config)

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
    val developer       = developerOne
    val developerEmail  = developerOne.emailAddress
    val adminEmail1     = adminOne.emailAddress
    val adminEmail2     = adminTwo.emailAddress
    val gatekeeperEmail = "gatekeeper@example.com".toLaxEmail
    val applicationName = ApplicationName("Test Application")
    val applicationId   = ApplicationId.random

    "not attempt to do anything if there are no recipients" in new Setup {
      // No stubbing so we cannot call POST

      await(connector.sendCollaboratorAddedConfirmation(adminOne, applicationName, recipients = Set.empty)) shouldBe HasSucceeded
    }

    "send added collaborator confirmation email" in new Setup {
      val expectedTemplateId                      = "apiAddedDeveloperAsCollaboratorConfirmation"
      val expectedToEmails                        = Set(developerEmail)
      val expectedParameters: Map[String, String] = Map(
        "article"           -> "an",
        "role"              -> "admin",
        "applicationName"   -> applicationName.value,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendCollaboratorAddedConfirmation(adminOne, applicationName, expectedToEmails))
    }

    "send added collaborator confirmation email with article for developer" in new Setup {
      val expectedTemplateId                      = "apiAddedDeveloperAsCollaboratorConfirmation"
      val expectedToEmails                        = Set(developerEmail)
      val expectedParameters: Map[String, String] = Map(
        "article"           -> "a",
        "role"              -> "developer",
        "applicationName"   -> applicationName.value,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendCollaboratorAddedConfirmation(developer, applicationName, expectedToEmails))
    }

    "send added collaborator notification email" in new Setup {

      val expectedTemplateId                      = "apiAddedDeveloperAsCollaboratorNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "email"             -> developer.emailAddress.text,
        "role"              -> "developer",
        "applicationName"   -> applicationName.value,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendCollaboratorAddedNotification(developer, applicationName, expectedToEmails))
    }

    "send removed collaborator confirmation email" in new Setup {

      val expectedTemplateId                      = "apiRemovedCollaboratorConfirmation"
      val expectedToEmails                        = Set(developerEmail)
      val expectedParameters: Map[String, String] = Map(
        "applicationName"   -> applicationName.value,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendRemovedCollaboratorConfirmation(applicationName, Set(developerEmail)))
    }

    "send removed collaborator notification email" in new Setup {

      val expectedTemplateId                      = "apiRemovedCollaboratorNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "email"             -> developerEmail.text,
        "applicationName"   -> applicationName.value,
        "developerHubTitle" -> hubTestTitle
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendRemovedCollaboratorNotification(developerEmail, applicationName, expectedToEmails))
    }

    "send application approved gatekeeper confirmation email" in new Setup {

      val expectedTemplateId                      = "apiApplicationApprovedGatekeeperConfirmation"
      val expectedToEmails                        = Set(gatekeeperEmail)
      val expectedParameters: Map[String, String] = Map(
        "email"           -> adminEmail1.text,
        "applicationName" -> applicationName.value
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
        "applicationName"  -> applicationName.value,
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
        "applicationName" -> applicationName.value
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendApplicationApprovedNotification(applicationName, Set(adminEmail1, adminEmail2)))
    }

    "send application deleted notification email" in new Setup {
      val expectedTemplateId                      = "apiApplicationDeletedNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> applicationName.value,
        "requestor"       -> adminEmail1.text,
        "applicationId"   -> applicationId.value.toString()
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendApplicationDeletedNotification(applicationName, applicationId, adminEmail1, expectedToEmails))
    }

    "send production credentials request expiry warning email" in new Setup {

      val expectedTemplateId                      = "apiProductionCredentialsRequestExpiryWarning"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> applicationName.value
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendProductionCredentialsRequestExpiryWarning(applicationName, expectedToEmails))
    }

    "send production credentials request expired email" in new Setup {

      val expectedTemplateId                      = "apiProductionCredentialsRequestExpired"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val expectedParameters: Map[String, String] = Map(
        "applicationName" -> applicationName.value
      )
      val expectedRequest                         = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendProductionCredentialsRequestExpired(applicationName, expectedToEmails))
    }

    "send added client secret notification email" in new Setup {
      val expectedTemplateId                      = "apiAddedClientSecretNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val clientSecretName: String                = "***cret"
      val expectedParameters: Map[String, String] = Map(
        "actorEmailAddress"  -> adminEmail1.text,
        "clientSecretEnding" -> "cret",
        "applicationName"    -> applicationName.value,
        "environmentName"    -> environmentName,
        "developerHubTitle"  -> hubTestTitle
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendAddedClientSecretNotification(adminEmail1, clientSecretName, applicationName.value, expectedToEmails))
    }

    "send removed client secret notification email" in new Setup {
      val expectedTemplateId                      = "apiRemovedClientSecretNotification"
      val expectedToEmails                        = Set(adminEmail1, adminEmail2)
      val clientSecretName: String                = "***cret"
      val expectedParameters: Map[String, String] = Map(
        "actorEmailAddress"  -> adminEmail1.text,
        "clientSecretEnding" -> "cret",
        "applicationName"    -> applicationName.value,
        "environmentName"    -> environmentName,
        "developerHubTitle"  -> hubTestTitle
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendRemovedClientSecretNotification(adminEmail1, clientSecretName, applicationName.value, expectedToEmails))
    }

    "send verify responsible individual notification email" in new Setup {
      val responsibleIndividualName               = "Bob Example"
      val responsibleIndividualEmail              = "bob@example.com".toLaxEmail
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
      val anAdminEmail                            = "admin@example.com".toLaxEmail
      val adminName                               = "John Admin"
      val appName                                 = ApplicationName("my app")
      val expectedParameters: Map[String, String] = Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> appName.value,
        "requesterName"             -> adminName
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(Set(anAdminEmail), "apiResponsibleIndividualReminderToAdmin", expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendVerifyResponsibleIndividualReminderToAdmin(responsibleIndividualName, anAdminEmail, appName, adminName))
    }

    "send responsible individual did not verify" in new Setup {
      val responsibleIndividualName               = "Bob Example"
      val anAdminEmail                            = "admin@example.com".toLaxEmail
      val adminName                               = "John Admin"
      val appName                                 = ApplicationName("my app")
      val expectedParameters: Map[String, String] = Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> appName.value,
        "requesterName"             -> adminName
      )
      val expectedRequest: SendEmailRequest       = SendEmailRequest(Set(anAdminEmail), "apiResponsibleIndividualDidNotVerify", expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendResponsibleIndividualDidNotVerify(responsibleIndividualName, anAdminEmail, appName, adminName))
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
      val recipients                              = Set("admin@example.com".toLaxEmail, "dev@example.com".toLaxEmail, "ri@example.com".toLaxEmail)
      val expectedRequest: SendEmailRequest       = SendEmailRequest(recipients, "apiChangeOfApplicationName", expectedParameters)
      emailWillReturn(expectedRequest)

      await(connector.sendChangeOfApplicationName(requesterName, previousAppName, newAppName, recipients))
    }

    "send change of application details" in new Setup {
      val requesterName                           = "bob@example.com"
      val applicationName                         = ApplicationName("App name")
      val fieldName                               = "privacy policy URL"
      val previousValue                           = "https://example.com/previous-privacy-policy"
      val newValue                                = "https://example.com/new-privacy-policy"
      val expectedParameters: Map[String, String] = Map(
        "requesterName"   -> requesterName,
        "applicationName" -> applicationName.value,
        "fieldName"       -> fieldName,
        "previousValue"   -> previousValue,
        "newValue"        -> newValue
      )
      val recipients                              = Set("admin@example.com".toLaxEmail, "dev@example.com".toLaxEmail, "ri@example.com".toLaxEmail)
      val expectedRequest: SendEmailRequest       = SendEmailRequest(recipients, "apiChangeOfApplicationDetails", expectedParameters)

      emailWillReturn(expectedRequest)

      val result = await(connector.sendChangeOfApplicationDetails(requesterName, applicationName, fieldName, previousValue, newValue, recipients))

      result shouldBe HasSucceeded
      verifySent()
    }

    "send change of application details with no values" in new Setup {
      val requesterName                           = "bob@example.com"
      val applicationName                         = ApplicationName("App name")
      val fieldName                               = "privacy policy URL"
      val expectedParameters: Map[String, String] = Map(
        "requesterName"   -> requesterName,
        "applicationName" -> applicationName.value,
        "fieldName"       -> fieldName
      )
      val recipients                              = Set("admin@example.com".toLaxEmail, "dev@example.com".toLaxEmail, "ri@example.com".toLaxEmail)
      val expectedRequest: SendEmailRequest       = SendEmailRequest(recipients, "apiChangeOfApplicationDetailsNoValue", expectedParameters)

      emailWillReturn(expectedRequest)

      val result = await(connector.sendChangeOfApplicationDetailsNoValue(requesterName, applicationName, fieldName, recipients))

      result shouldBe HasSucceeded
      verifySent()
    }

    "send verify responsible individual update notification" in new Setup {
      val responsibleIndividualName  = "Bob Example"
      val responsibleIndividualEmail = "bob@example.com".toLaxEmail
      val adminName                  = "John Admin"
      val appName                    = "app name"
      val verificationId             = ResponsibleIndividualVerificationId.random.value

      val expectedParameters: Map[String, String] = Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> appName,
        "requesterName"             -> adminName,
        "developerHubLink"          -> s"$hubUrl/developer/submissions/responsible-individual-verification?code=$verificationId"
      )
      val recipients                              = Set(responsibleIndividualEmail)
      val expectedRequest: SendEmailRequest       = SendEmailRequest(recipients, "apiVerifyResponsibleIndividualUpdate", expectedParameters)

      emailWillReturn(expectedRequest)

      val result = await(connector.sendVerifyResponsibleIndividualUpdateNotification(responsibleIndividualName, responsibleIndividualEmail, appName, adminName, verificationId))

      result shouldBe HasSucceeded
      verifySent()
    }
  }
}
