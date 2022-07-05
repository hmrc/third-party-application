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

import play.api.libs.json.Json
import play.mvc.Http.Status._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.HttpClient
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

import javax.inject.Inject
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

object EmailConnector {
  case class Config(baseUrl: String, devHubBaseUrl: String, devHubTitle: String, environmentName: String)

  private[connector] case class SendEmailRequest(
      to: Set[String],
      templateId: String,
      parameters: Map[String, String],
      force: Boolean = false,
      auditData: Map[String, String] = Map.empty,
      eventUrl: Option[String] = None
    )

  private[connector] object SendEmailRequest {
    implicit val sendEmailRequestFmt = Json.format[SendEmailRequest]
  }
}

@Singleton
class EmailConnector @Inject() (httpClient: HttpClient, config: EmailConnector.Config)(implicit val ec: ExecutionContext) extends ApplicationLogger {
  import EmailConnector._

  val serviceUrl      = config.baseUrl
  val devHubBaseUrl   = config.devHubBaseUrl
  val devHubTitle     = config.devHubTitle
  val environmentName = config.environmentName

  val addedCollaboratorConfirmation             = "apiAddedDeveloperAsCollaboratorConfirmation"
  val addedCollaboratorNotification             = "apiAddedDeveloperAsCollaboratorNotification"
  val removedCollaboratorConfirmation           = "apiRemovedCollaboratorConfirmation"
  val removedCollaboratorNotification           = "apiRemovedCollaboratorNotification"
  val applicationApprovedGatekeeperConfirmation = "apiApplicationApprovedGatekeeperConfirmation"
  val applicationApprovedAdminConfirmation      = "apiApplicationApprovedAdminConfirmation"
  val applicationApprovedNotification           = "apiApplicationApprovedNotification"
  val applicationRejectedNotification           = "apiApplicationRejectedNotification"
  val applicationDeletedNotification            = "apiApplicationDeletedNotification"
  val addedClientSecretNotification             = "apiAddedClientSecretNotification"
  val removedClientSecretNotification           = "apiRemovedClientSecretNotification"
  val verifyResponsibleIndividual               = "apiVerifyResponsibleIndividual"
  val responsibleIndividualReminderToAdmin      = "apiResponsibleIndividualReminderToAdmin"
  val responsibleIndividualDidNotVerify         = "apiResponsibleIndividualDidNotVerify"
  val responsibleIndividualDeclined             = "apiResponsibleIndividualDeclined"
  val changeOfApplicationName                   = "apiChangeOfApplicationName"
  val changeOfApplicationDetails                = "apiChangeOfApplicationDetails"

  def sendAddedCollaboratorConfirmation(role: String, application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val article = if (role == "admin") "an" else "a"

    post(SendEmailRequest(
      recipients,
      addedCollaboratorConfirmation,
      Map(
        "article"           -> article,
        "role"              -> role,
        "applicationName"   -> application,
        "developerHubTitle" -> devHubTitle
      )
    ))
      .map(_ => HasSucceeded)
  }

  def sendAddedCollaboratorNotification(email: String, role: String, application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, addedCollaboratorNotification, Map("email" -> email, "role" -> s"$role", "applicationName" -> application, "developerHubTitle" -> devHubTitle)))
      .map(_ => HasSucceeded)
  }

  def sendRemovedCollaboratorConfirmation(application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, removedCollaboratorConfirmation, Map("applicationName" -> application, "developerHubTitle" -> devHubTitle)))
      .map(_ => HasSucceeded)
  }

  def sendRemovedCollaboratorNotification(email: String, application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, removedCollaboratorNotification, Map("email" -> email, "applicationName" -> application, "developerHubTitle" -> devHubTitle)))
  }

  def sendApplicationApprovedGatekeeperConfirmation(email: String, application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, applicationApprovedGatekeeperConfirmation, Map("email" -> email, "applicationName" -> application)))
  }

  def sendApplicationApprovedAdminConfirmation(application: String, code: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      applicationApprovedAdminConfirmation,
      Map("applicationName" -> application, "developerHubLink" -> s"$devHubBaseUrl/developer/application-verification?code=$code")
    ))
  }

  def sendApplicationApprovedNotification(application: String, recipients: Set[String])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, applicationApprovedNotification, Map("applicationName" -> application)))
  }

  def sendApplicationRejectedNotification(application: String, recipients: Set[String], reason: String)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      applicationRejectedNotification,
      Map(
        "applicationName" -> application,
        "guidelinesUrl"   -> s"$devHubBaseUrl/api-documentation/docs/using-the-hub/name-guidelines",
        "supportUrl"      -> s"$devHubBaseUrl/developer/support",
        "reason"          -> reason
      )
    ))
  }

  def sendApplicationDeletedNotification(
      applicationName: String,
      applicationId: ApplicationId,
      requesterEmail: String,
      recipients: Set[String]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(
      SendEmailRequest(
        recipients,
        applicationDeletedNotification,
        Map("applicationName" -> applicationName, "requestor" -> requesterEmail, "applicationId" -> applicationId.value.toString)
      )
    )
  }

  def sendAddedClientSecretNotification(
      actorEmailAddress: String,
      clientSecretName: String,
      applicationName: String,
      recipients: Set[String]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    sendClientSecretNotification(addedClientSecretNotification, actorEmailAddress, clientSecretName, applicationName, recipients)
  }

  def sendRemovedClientSecretNotification(
      actorEmailAddress: String,
      clientSecretName: String,
      applicationName: String,
      recipients: Set[String]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    sendClientSecretNotification(removedClientSecretNotification, actorEmailAddress, clientSecretName, applicationName, recipients)
  }

  private def sendClientSecretNotification(
      templateId: String,
      actorEmailAddress: String,
      clientSecretName: String,
      applicationName: String,
      recipients: Set[String]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      templateId,
      Map(
        "actorEmailAddress"  -> actorEmailAddress,
        "clientSecretEnding" -> clientSecretName.takeRight(4), // scalastyle:off magic.number
        "applicationName"    -> applicationName,
        "environmentName"    -> environmentName,
        "developerHubTitle"  -> devHubTitle
      )
    ))
  }

  def sendVerifyResponsibleIndividualNotification(
      responsibleIndividualName: String,
      responsibleIndividualEmailAddress: String,
      applicationName: String,
      requesterName: String,
      verifyResponsibleIndividualUniqueId: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(responsibleIndividualEmailAddress),
      verifyResponsibleIndividual,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName,
        "requesterName"             -> requesterName,
        "developerHubLink"          -> s"$devHubBaseUrl/developer/submissions/responsible-individual-verification?code=$verifyResponsibleIndividualUniqueId"
      )
    ))
  }

  def sendVerifyResponsibleIndividualReminderToAdmin(
      responsibleIndividualName: String,
      adminEmailAddress: String,
      applicationName: String,
      requesterName: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(adminEmailAddress),
      responsibleIndividualReminderToAdmin,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName,
        "requesterName"             -> requesterName
      )
    ))
  }

  def sendResponsibleIndividualDidNotVerify(
      responsibleIndividualName: String,
      adminEmailAddress: String,
      applicationName: String,
      requesterName: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(adminEmailAddress),
      responsibleIndividualDidNotVerify,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName,
        "requesterName"             -> requesterName
      )
    ))
  }

  def sendResponsibleIndividualDeclined(
      responsibleIndividualName: String,
      adminEmailAddress: String,
      applicationName: String,
      requesterName: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(adminEmailAddress),
      responsibleIndividualDeclined,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName,
        "requesterName"             -> requesterName
      )
    ))
  }

  def sendChangeOfApplicationName(
      requesterName: String,
      previousApplicationName: String,
      newApplicationName: String,
      recipients: Set[String]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      changeOfApplicationName,
      Map(
        "requesterName"           -> requesterName,
        "previousApplicationName" -> previousApplicationName,
        "newApplicationName"      -> newApplicationName
      )
    ))
  }

  def sendChangeOfApplicationDetails(
      requesterName: String,
      applicationName: String,
      fieldName: String,
      previousValue: String,
      newValue: String,
      recipients: Set[String]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      changeOfApplicationDetails,
      Map(
        "requesterName"   -> requesterName,
        "applicationName" -> applicationName,
        "fieldName"       -> fieldName,
        "previousValue"   -> previousValue,
        "newValue"        -> newValue
      )
    ))
  }

  private def post(payload: SendEmailRequest)(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val url = s"$serviceUrl/hmrc/email"

    def extractError(response: HttpResponse): RuntimeException = {
      Try(response.json \ "message") match {
        case Success(jsValue) => new RuntimeException(jsValue.as[String])
        case Failure(_)       => new RuntimeException(
            s"Unable send email. Unexpected error for url=$url status=${response.status} response=${response.body}"
          )
      }
    }

    import uk.gov.hmrc.http.HttpReads.Implicits._

    httpClient.POST[SendEmailRequest, HttpResponse](url, payload)
      .map { response =>
        logger.info(s"Sent '${payload.templateId}' to: ${payload.to.mkString(",")} with response: ${response.status}")
        response.status match {
          case status if status >= 200 && status <= 299 => HasSucceeded
          case NOT_FOUND                                => throw new RuntimeException(s"Unable to send email. Downstream endpoint not found: $url")
          case _                                        => throw extractError(response)
        }
      }
  }
}
