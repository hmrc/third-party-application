/*
 * Copyright 2020 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.Json
import play.mvc.Http.Status._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class SendEmailRequest(to: Set[String],
                            templateId: String,
                            parameters: Map[String, String],
                            force: Boolean = false,
                            auditData: Map[String, String] = Map.empty,
                            eventUrl: Option[String] = None)

object SendEmailRequest {
  implicit val sendEmailRequestFmt = Json.format[SendEmailRequest]
}

@Singleton
class EmailConnector @Inject()(httpClient: HttpClient, config: EmailConfig)(implicit val ec: ExecutionContext) {
  val serviceUrl = config.baseUrl
  val devHubBaseUrl = config.devHubBaseUrl
  val devHubTitle = config.devHubTitle
  val environmentName = config.environmentName

  val addedCollaboratorConfirmation = "apiAddedDeveloperAsCollaboratorConfirmation"
  val addedCollaboratorNotification = "apiAddedDeveloperAsCollaboratorNotification"
  val removedCollaboratorConfirmation = "apiRemovedCollaboratorConfirmation"
  val removedCollaboratorNotification = "apiRemovedCollaboratorNotification"
  val applicationApprovedGatekeeperConfirmation = "apiApplicationApprovedGatekeeperConfirmation"
  val applicationApprovedAdminConfirmation = "apiApplicationApprovedAdminConfirmation"
  val applicationApprovedNotification = "apiApplicationApprovedNotification"
  val applicationRejectedNotification = "apiApplicationRejectedNotification"
  val applicationDeletedNotification = "apiApplicationDeletedNotification"
  val addedClientSecretNotification = "apiAddedClientSecretNotification"
  val removedClientSecretNotification = "apiRemovedClientSecretNotification"

  def sendAddedCollaboratorConfirmation(role: String, application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    post(SendEmailRequest(recipients, addedCollaboratorConfirmation,
      Map("role" -> role,
        "applicationName" -> application,
        "developerHubLink" -> s"$devHubBaseUrl/developer/registration",
        "developerHubTitle" -> devHubTitle)))
  }

  def sendAddedCollaboratorNotification(email: String, role: String, application: String, recipients: Set[String])
                                       (implicit hc: HeaderCarrier): Future[HttpResponse] = {
    post(SendEmailRequest(recipients, addedCollaboratorNotification,
      Map("email" -> email, "role" -> s"$role", "applicationName" -> application, "developerHubTitle" -> devHubTitle)))
  }

  def sendRemovedCollaboratorConfirmation(application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    post(SendEmailRequest(recipients, removedCollaboratorConfirmation,
      Map("applicationName" -> application, "developerHubTitle" -> devHubTitle)))
  }

  def sendRemovedCollaboratorNotification(email: String, application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    post(SendEmailRequest(recipients, removedCollaboratorNotification,
      Map("email" -> email, "applicationName" -> application, "developerHubTitle" -> devHubTitle)))
  }

  def sendApplicationApprovedGatekeeperConfirmation(email: String, application: String, recipients: Set[String])(implicit hc: HeaderCarrier) = {
    post(SendEmailRequest(recipients, applicationApprovedGatekeeperConfirmation,
      Map("email" -> email, "applicationName" -> application)))
  }

  def sendApplicationApprovedAdminConfirmation(application: String, code: String, recipients: Set[String])(implicit hc: HeaderCarrier) = {
    post(SendEmailRequest(recipients, applicationApprovedAdminConfirmation,
      Map("applicationName" -> application,
        "developerHubLink" -> s"$devHubBaseUrl/developer/application-verification?code=$code")))
  }

  def sendApplicationApprovedNotification(application: String, recipients: Set[String])(implicit hc: HeaderCarrier) = {
    post(SendEmailRequest(recipients, applicationApprovedNotification,
      Map("applicationName" -> application)))
  }

  def sendApplicationRejectedNotification(application: String, recipients: Set[String], reason: String)(implicit hc: HeaderCarrier) = {
    post(SendEmailRequest(recipients, applicationRejectedNotification,
      Map("applicationName" -> application,
        "guidelinesUrl" -> s"$devHubBaseUrl/api-documentation/docs/using-the-hub/name-guidelines",
        "supportUrl" -> s"$devHubBaseUrl/developer/support",
        "reason" -> reason)))
  }

  def sendApplicationDeletedNotification(application: String, requesterEmail: String, recipients: Set[String])(implicit hc: HeaderCarrier) = {
    post(SendEmailRequest(recipients, applicationDeletedNotification,
      Map("applicationName" -> application, "requestor" -> requesterEmail)))
  }

  def sendAddedClientSecretNotification(actorEmailAddress: String,
                                        clientSecret: String,
                                        applicationName: String,
                                        recipients: Set[String])(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    sendClientSecretNotification(addedClientSecretNotification, actorEmailAddress, clientSecret, applicationName, recipients)
  }

  def sendRemovedClientSecretNotification(actorEmailAddress: String,
                                          clientSecret: String,
                                          applicationName: String,
                                          recipients: Set[String])(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    sendClientSecretNotification(removedClientSecretNotification, actorEmailAddress, clientSecret, applicationName, recipients)
  }

  private def sendClientSecretNotification(templateId: String,
                                           actorEmailAddress: String,
                                           clientSecret: String,
                                           applicationName: String,
                                           recipients: Set[String])(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    post(SendEmailRequest(recipients, templateId,
      Map(
        "actorEmailAddress" -> actorEmailAddress,
        "clientSecretEnding" -> clientSecret.takeRight(4), // scalastyle:off magic.number
        "applicationName" -> applicationName,
        "environmentName" -> environmentName,
        "developerHubTitle" -> devHubTitle
      )))
  }

  private def post(payload: SendEmailRequest)(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    val url = s"$serviceUrl/hmrc/email"

    def extractError(response: HttpResponse): RuntimeException = {
      Try(response.json \ "message") match {
        case Success(jsValue) => new RuntimeException(jsValue.as[String])
        case Failure(_) => new RuntimeException(
          s"Unable send email. Unexpected error for url=$url status=${response.status} response=${response.body}")
      }
    }

    httpClient.POST[SendEmailRequest, HttpResponse](url, payload)
      .map { response =>
        Logger.info(s"Sent '${payload.templateId}' to: ${payload.to.mkString(",")} with response: ${response.status}")
        response.status match {
          case status if status >= 200 && status <= 299 => response
          case NOT_FOUND => throw new RuntimeException(s"Unable to send email. Downstream endpoint not found: $url")
          case _ => throw extractError(response)
        }
      }
  }
}

case class EmailConfig(baseUrl: String, devHubBaseUrl: String, devHubTitle: String, environmentName: String)
