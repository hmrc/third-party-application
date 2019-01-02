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

package unit.connector

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import play.api.http.Status
import uk.gov.hmrc.config.AppContext
import uk.gov.hmrc.connector.EmailConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.{UnitSpec, WithFakeApplication}

class EmailConnectorSpec extends UnitSpec with WithFakeApplication with MockitoSugar with ScalaFutures with BeforeAndAfterEach {
    implicit val hc = HeaderCarrier()
    val stubPort = sys.env.getOrElse("WIREMOCK", "21212").toInt
    val stubHost = "localhost"
    val wireMockUrl = s"http://$stubHost:$stubPort"
    val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))
    val emailServicePath = "/hmrc/email"
    val hubTestTitle = "Unit Test Hub Title"

    trait Setup {
        val appContext = mock[AppContext]
        when(appContext.devHubBaseUrl).thenReturn("http://localhost:9685")
        when(appContext.devHubTitle).thenReturn(hubTestTitle)

        val connector = new EmailConnector(appContext) {
            override lazy val serviceUrl = wireMockUrl
        }
        stubFor(post(urlEqualTo(emailServicePath)).willReturn(aResponse().withStatus(Status.OK)))
    }

    override def beforeEach() {
        wireMockServer.start()
        WireMock.configureFor(stubHost, stubPort)
    }

    override def afterEach() {
        wireMockServer.resetMappings()
        wireMockServer.stop()
    }

    "emailConnector" should {
        val collaboratorEmail = "email@example.com"
        val adminEmail1 = "admin1@example.com"
        val adminEmail2 = "admin2@example.com"
        val gatekeeperEmail = "gatekeeper@example.com"
        val role = "admin"
        val application = "Test Application"

        "send added collaborator confirmation email" in new Setup {
            await(connector.sendAddedCollaboratorConfirmation(role, application, Set(collaboratorEmail)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$collaboratorEmail"],
                     |  "templateId": "apiAddedDeveloperAsCollaboratorConfirmation",
                     |  "parameters": {
                     |    "role": "$role",
                     |    "applicationName": "$application",
                     |    "developerHubLink": "${connector.devHubBaseUrl}/developer/registration",
                     |    "developerHubTitle": "$hubTestTitle"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin)))
        }

        "send added collaborator notification email" in new Setup {
            await(connector.sendAddedCollaboratorNotification(collaboratorEmail, role, application, Set(adminEmail1, adminEmail2)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$adminEmail1","$adminEmail2"],
                     |  "templateId": "apiAddedDeveloperAsCollaboratorNotification",
                     |  "parameters": {
                     |    "email": "$collaboratorEmail",
                     |    "role": "$role",
                     |    "applicationName": "$application",
                     |    "developerHubTitle":"$hubTestTitle"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin))
            )
        }

        "send removed collaborator confirmation email" in new Setup {
            await(connector.sendRemovedCollaboratorConfirmation(application, Set(collaboratorEmail)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$collaboratorEmail"],
                     |  "templateId": "apiRemovedCollaboratorConfirmation",
                     |  "parameters": {
                     |    "applicationName": "$application",
                     |    "developerHubTitle": "$hubTestTitle"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin)))
        }

        "send removed collaborator notification email" in new Setup {
            await(connector.sendRemovedCollaboratorNotification(collaboratorEmail, application,  Set(adminEmail1, adminEmail2)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$adminEmail1","$adminEmail2"],
                     |  "templateId":"apiRemovedCollaboratorNotification",
                     |  "parameters": {
                     |    "email": "$collaboratorEmail",
                     |    "applicationName": "$application",
                     |    "developerHubTitle": "$hubTestTitle"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin))
            )
        }

        "send application approved gatekeeper confirmation email" in new Setup {
            await(connector.sendApplicationApprovedGatekeeperConfirmation(adminEmail1, application, Set(gatekeeperEmail)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$gatekeeperEmail"],
                     |  "templateId": "apiApplicationApprovedGatekeeperConfirmation",
                     |  "parameters": {
                     |    "email": "$adminEmail1",
                     |    "applicationName": "$application"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin)))
        }

        "send application approved admin confirmation email" in new Setup {
            val code: String = "generatedCode"
            await(connector.sendApplicationApprovedAdminConfirmation(application, code, Set(adminEmail1)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$adminEmail1"],
                     |  "templateId": "apiApplicationApprovedAdminConfirmation",
                     |  "parameters": {
                     |    "applicationName":"$application",
                     |    "developerHubLink":"${connector.devHubBaseUrl}/developer/application-verification?code=$code"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin)))
        }

        "send application approved notification email" in new Setup {
            await(connector.sendApplicationApprovedNotification(application, Set(adminEmail1, adminEmail2)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$adminEmail1","$adminEmail2"],
                     |  "templateId": "apiApplicationApprovedNotification",
                     |  "parameters": {
                     |    "applicationName": "$application"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin)))
        }

        "send application rejected notification email" in new Setup {
            val reason = "Test Error"
            await(connector.sendApplicationRejectedNotification(application, Set(adminEmail1, adminEmail2), reason))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                s"""{
                   |  "to": ["$adminEmail1","$adminEmail2"],
                   |  "templateId": "apiApplicationRejectedNotification",
                   |  "parameters": {
                   |    "applicationName": "$application",
                   |    "guidelinesUrl": "${connector.devHubBaseUrl}/api-documentation/docs/using-the-hub/name-guidelines",
                   |    "supportUrl": "${connector.devHubBaseUrl}/developer/support",
                   |    "reason": "$reason"
                   |  },
                   |  "force": false,
                   |  "auditData": {}
                   |}""".stripMargin)))
        }

        "send application deleted notification email" in new Setup {
            await(connector.sendApplicationDeletedNotification(application, adminEmail1, Set(adminEmail1, adminEmail2)))

            verify(1, postRequestedFor(urlEqualTo(emailServicePath))
              .withRequestBody(equalToJson(
                  s"""{
                     |  "to": ["$adminEmail1","$adminEmail2"],
                     |  "templateId": "apiApplicationDeletedNotification",
                     |  "parameters": {
                     |    "applicationName": "$application",
                     |    "requestor": "$adminEmail1"
                     |  },
                     |  "force": false,
                     |  "auditData": {}
                     |}""".stripMargin)))
        }
    }
}