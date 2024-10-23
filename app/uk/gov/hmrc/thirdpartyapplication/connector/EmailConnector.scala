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

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import play.api.libs.json.{Json, OFormat}
import play.mvc.Http.Status._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{Collaborator, Collaborators}
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName

object EmailConnector {
  case class Config(baseUrl: String, devHubBaseUrl: String, devHubTitle: String, environmentName: String)

  private[connector] case class SendEmailRequest(
      to: Set[LaxEmailAddress],
      templateId: String,
      parameters: Map[String, String],
      force: Boolean = false,
      auditData: Map[String, String] = Map.empty,
      eventUrl: Option[String] = None
    )

  private[connector] object SendEmailRequest {
    implicit val sendEmailRequestFmt: OFormat[SendEmailRequest] = Json.format[SendEmailRequest]
  }
}

@Singleton
class EmailConnector @Inject() (httpClient: HttpClientV2, config: EmailConnector.Config)(implicit val ec: ExecutionContext) extends ApplicationLogger {
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
  val applicationDeletedNotification            = "apiApplicationDeletedNotification"
  val productionCredentialsRequestExpiryWarning = "apiProductionCredentialsRequestExpiryWarning"
  val productionCredentialsRequestExpired       = "apiProductionCredentialsRequestExpired"
  val addedClientSecretNotification             = "apiAddedClientSecretNotification"
  val removedClientSecretNotification           = "apiRemovedClientSecretNotification"
  val verifyResponsibleIndividual               = "apiVerifyResponsibleIndividual"
  val verifyResponsibleIndividualUpdate         = "apiVerifyResponsibleIndividualUpdate"
  val responsibleIndividualReminderToAdmin      = "apiResponsibleIndividualReminderToAdmin"
  val responsibleIndividualDidNotVerify         = "apiResponsibleIndividualDidNotVerify"
  val responsibleIndividualDeclined             = "apiResponsibleIndividualDeclined"
  val responsibleIndividualDeclinedV2           = "apiResponsibleIndividualDeclinedV2"
  val responsibleIndividualNotChanged           = "apiResponsibleIndividualNotChanged"
  val changeOfApplicationName                   = "apiChangeOfApplicationName"
  val changeOfApplicationDetails                = "apiChangeOfApplicationDetails"
  val changeOfApplicationDetailsNoValue         = "apiChangeOfApplicationDetailsNoValue"
  val changeOfResponsibleIndividual             = "apiChangeOfResponsibleIndividual"
  val newTermsOfUseInvitation                   = "apiNewTermsOfUseInvitation"
  val newTermsOfUseConfirmation                 = "apiNewTermsOfUseConfirmation"

  private def getRoleForDisplay(role: Collaborator) =
    role match {
      case _: Collaborators.Administrator => "admin"
      case _: Collaborators.Developer     => "developer"
    }

  def sendCollaboratorAddedConfirmation(collaborator: Collaborator, application: String, recipients: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val article = if (collaborator.isAdministrator) "an" else "a"

    post(SendEmailRequest(
      recipients,
      addedCollaboratorConfirmation,
      Map(
        "article"           -> article,
        "role"              -> getRoleForDisplay(collaborator),
        "applicationName"   -> application,
        "developerHubTitle" -> devHubTitle
      )
    ))
      .map(_ => HasSucceeded)
  }

  def sendCollaboratorAddedNotification(collaborator: Collaborator, application: String, recipients: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      addedCollaboratorNotification,
      Map("email" -> collaborator.emailAddress.text, "role" -> s"${getRoleForDisplay(collaborator)}", "applicationName" -> application, "developerHubTitle" -> devHubTitle)
    ))
      .map(_ => HasSucceeded)
  }

  def sendRemovedCollaboratorConfirmation(application: String, recipients: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, removedCollaboratorConfirmation, Map("applicationName" -> application, "developerHubTitle" -> devHubTitle)))
      .map(_ => HasSucceeded)
  }

  def sendRemovedCollaboratorNotification(email: LaxEmailAddress, application: String, recipients: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, removedCollaboratorNotification, Map("email" -> email.text, "applicationName" -> application, "developerHubTitle" -> devHubTitle)))
  }

  def sendApplicationApprovedGatekeeperConfirmation(email: LaxEmailAddress, application: String, recipients: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, applicationApprovedGatekeeperConfirmation, Map("email" -> email.text, "applicationName" -> application)))
  }

  def sendApplicationApprovedAdminConfirmation(application: String, code: String, recipients: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      applicationApprovedAdminConfirmation,
      Map("applicationName" -> application, "developerHubLink" -> s"$devHubBaseUrl/developer/application-verification?code=$code")
    ))
  }

  def sendApplicationApprovedNotification(application: String, recipients: Set[LaxEmailAddress])(implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    post(SendEmailRequest(recipients, applicationApprovedNotification, Map("applicationName" -> application)))
  }

  def sendApplicationDeletedNotification(
      applicationName: ApplicationName,
      applicationId: ApplicationId,
      requesterEmail: LaxEmailAddress,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(
      SendEmailRequest(
        recipients,
        applicationDeletedNotification,
        Map("applicationName" -> applicationName.value, "requestor" -> requesterEmail.text, "applicationId" -> applicationId.value.toString)
      )
    )
  }

  def sendProductionCredentialsRequestExpiryWarning(
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(
      SendEmailRequest(
        recipients,
        productionCredentialsRequestExpiryWarning,
        Map("applicationName" -> applicationName.value)
      )
    )
  }

  def sendProductionCredentialsRequestExpired(
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(
      SendEmailRequest(
        recipients,
        productionCredentialsRequestExpired,
        Map("applicationName" -> applicationName.value)
      )
    )
  }

  def sendAddedClientSecretNotification(
      actorEmailAddress: LaxEmailAddress,
      clientSecretName: String,
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    sendClientSecretNotification(addedClientSecretNotification, actorEmailAddress.text, clientSecretName, applicationName, recipients)
  }

  def sendRemovedClientSecretNotification(
      actorEmailAddress: LaxEmailAddress,
      clientSecretName: String,
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    sendClientSecretNotification(removedClientSecretNotification, actorEmailAddress.text, clientSecretName, applicationName, recipients)
  }

  private def sendClientSecretNotification(
      templateId: String,
      actorEmailAddress: String,
      clientSecretName: String,
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      templateId,
      Map(
        "actorEmailAddress"  -> actorEmailAddress,
        "clientSecretEnding" -> clientSecretName.takeRight(4), // scalastyle:off magic.number
        "applicationName"    -> applicationName.value,
        "environmentName"    -> environmentName,
        "developerHubTitle"  -> devHubTitle
      )
    ))
  }

  def sendVerifyResponsibleIndividualNotification(
      responsibleIndividualName: String,
      responsibleIndividualEmailAddress: LaxEmailAddress,
      applicationName: ApplicationName,
      requesterName: String,
      verifyResponsibleIndividualUniqueId: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(responsibleIndividualEmailAddress),
      verifyResponsibleIndividual,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName.value,
        "requesterName"             -> requesterName,
        "developerHubLink"          -> s"$devHubBaseUrl/developer/submissions/responsible-individual-verification?code=$verifyResponsibleIndividualUniqueId"
      )
    ))
  }

  def sendVerifyResponsibleIndividualUpdateNotification(
      responsibleIndividualName: String,
      responsibleIndividualEmailAddress: LaxEmailAddress,
      applicationName: ApplicationName,
      requesterName: String,
      verifyResponsibleIndividualUniqueId: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(responsibleIndividualEmailAddress),
      verifyResponsibleIndividualUpdate,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName.value,
        "requesterName"             -> requesterName,
        "developerHubLink"          -> s"$devHubBaseUrl/developer/submissions/responsible-individual-verification?code=$verifyResponsibleIndividualUniqueId"
      )
    ))
  }

  def sendVerifyResponsibleIndividualReminderToAdmin(
      responsibleIndividualName: String,
      adminEmailAddress: LaxEmailAddress,
      applicationName: ApplicationName,
      requesterName: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(adminEmailAddress),
      responsibleIndividualReminderToAdmin,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName.value,
        "requesterName"             -> requesterName
      )
    ))
  }

  def sendResponsibleIndividualDidNotVerify(
      responsibleIndividualName: String,
      adminEmailAddress: LaxEmailAddress,
      applicationName: ApplicationName,
      requesterName: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(adminEmailAddress),
      responsibleIndividualDidNotVerify,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName.value,
        "requesterName"             -> requesterName
      )
    ))
  }

  def sendResponsibleIndividualDeclined(
      responsibleIndividualName: String,
      adminEmailAddress: LaxEmailAddress,
      applicationName: ApplicationName,
      requesterName: String
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      Set(adminEmailAddress),
      responsibleIndividualDeclined,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName.value,
        "requesterName"             -> requesterName
      )
    ))
  }

  def sendResponsibleIndividualDeclinedOrDidNotVerify(
      responsibleIndividualName: String,
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      responsibleIndividualDeclinedV2,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName.value
      )
    ))
  }

  def sendResponsibleIndividualNotChanged(
      responsibleIndividualName: String,
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      responsibleIndividualNotChanged,
      Map(
        "responsibleIndividualName" -> responsibleIndividualName,
        "applicationName"           -> applicationName.value
      )
    ))
  }

  def sendChangeOfApplicationName(
      requesterName: String,
      previousApplicationName: ApplicationName,
      newApplicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      changeOfApplicationName,
      Map(
        "requesterName"           -> requesterName,
        "previousApplicationName" -> previousApplicationName.value,
        "newApplicationName"      -> newApplicationName.value
      )
    ))
  }

  def sendChangeOfApplicationDetails(
      requesterName: String,
      applicationName: ApplicationName,
      fieldName: String,
      previousValue: String,
      newValue: String,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      changeOfApplicationDetails,
      Map(
        "requesterName"   -> requesterName,
        "applicationName" -> applicationName.value,
        "fieldName"       -> fieldName,
        "previousValue"   -> previousValue,
        "newValue"        -> newValue
      )
    ))
  }

  def sendChangeOfApplicationDetailsNoValue(
      requesterName: String,
      applicationName: ApplicationName,
      fieldName: String,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      changeOfApplicationDetailsNoValue,
      Map(
        "requesterName"   -> requesterName,
        "applicationName" -> applicationName.value,
        "fieldName"       -> fieldName
      )
    ))
  }

  def sendChangeOfResponsibleIndividual(
      requesterName: String,
      applicationName: ApplicationName,
      previousResponsibleIndividual: String,
      newResponsibleIndividual: String,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] = {
    post(SendEmailRequest(
      recipients,
      changeOfResponsibleIndividual,
      Map(
        "requesterName"                 -> requesterName,
        "applicationName"               -> applicationName.value,
        "previousResponsibleIndividual" -> previousResponsibleIndividual,
        "newResponsibleIndividual"      -> newResponsibleIndividual
      )
    ))
  }

  def sendNewTermsOfUseInvitation(
      dueBy: Instant,
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] =
    post(
      SendEmailRequest(
        recipients,
        newTermsOfUseInvitation,
        Map(
          "completeBy"      -> DateTimeFormatter.ofPattern("dd MMMM yyyy").withZone(ZoneId.systemDefault()).format(dueBy),
          "applicationName" -> applicationName.value
        )
      )
    )

  def sendNewTermsOfUseConfirmation(
      applicationName: ApplicationName,
      recipients: Set[LaxEmailAddress]
    )(implicit hc: HeaderCarrier
    ): Future[HasSucceeded] =
    post(
      SendEmailRequest(
        recipients,
        newTermsOfUseConfirmation,
        Map(
          "applicationName" -> applicationName.value
        )
      )
    )

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

    def makeCall() = {
      import uk.gov.hmrc.http.HttpReads.Implicits._

      httpClient
        .post(url"$url")
        .withBody(Json.toJson(payload))
        .execute[HttpResponse]
        .map { response =>
          logger.info(s"Sent '${payload.templateId}' with response: ${response.status}")
          response.status match {
            case status if status >= 200 && status <= 299 => HasSucceeded
            case NOT_FOUND                                => throw new RuntimeException(s"Unable to send email. Downstream endpoint not found: $url")
            case _                                        => throw extractError(response)
          }
        }
    }

    if (payload.to.isEmpty) {
      logger.warn(s"Sending email ${payload.templateId} abandoned due to lack of any recipients")
      Future.successful(HasSucceeded)
    } else {
      makeCall()
    }
  }
}
