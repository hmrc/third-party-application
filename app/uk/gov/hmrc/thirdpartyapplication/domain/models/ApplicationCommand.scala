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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import java.time.LocalDateTime

import play.api.libs.json.Json
import uk.gov.hmrc.play.json.Union

import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.Actor

trait ApplicationCommand {
  def timestamp: LocalDateTime
}
case class AddClientSecret(actor: Actor, clientSecret: ClientSecret, timestamp: LocalDateTime)                                                          extends ApplicationCommand
case class RemoveClientSecret(actor: Actor, clientSecretId: String, timestamp: LocalDateTime)                                                           extends ApplicationCommand
case class AddCollaborator(actor: Actor, collaborator: Collaborator, adminsToEmail: Set[String], timestamp: LocalDateTime)                              extends ApplicationCommand
case class RemoveCollaborator(actor: Actor, collaborator: Collaborator, adminsToEmail: Set[String], timestamp: LocalDateTime)                           extends ApplicationCommand
case class ChangeProductionApplicationPrivacyPolicyLocation(instigator: UserId, timestamp: LocalDateTime, newLocation: PrivacyPolicyLocation)           extends ApplicationCommand
case class ChangeProductionApplicationTermsAndConditionsLocation(instigator: UserId, timestamp: LocalDateTime, newLocation: TermsAndConditionsLocation) extends ApplicationCommand
case class ChangeResponsibleIndividualToSelf(instigator: UserId, timestamp: LocalDateTime, name: String, email: String)                                 extends ApplicationCommand
case class ChangeResponsibleIndividualToOther(code: String, timestamp: LocalDateTime)                                                                   extends ApplicationCommand
case class VerifyResponsibleIndividual(instigator: UserId, timestamp: LocalDateTime, requesterName: String, riName: String, riEmail: String)            extends ApplicationCommand
case class DeclineResponsibleIndividual(code: String, timestamp: LocalDateTime)                                                                         extends ApplicationCommand
case class DeclineResponsibleIndividualDidNotVerify(code: String, timestamp: LocalDateTime)                                                             extends ApplicationCommand
case class DeleteApplicationByCollaborator(instigator: UserId, reasons: String, timestamp: LocalDateTime)                                               extends ApplicationCommand
case class DeleteProductionCredentialsApplication(jobId: String, reasons: String, timestamp: LocalDateTime)                                             extends ApplicationCommand
case class DeleteUnusedApplication(jobId: String, authorisationKey: String, reasons: String, timestamp: LocalDateTime)                                  extends ApplicationCommand
case class SubscribeToApi(actor: Actor, apiIdentifier: ApiIdentifier, timestamp: LocalDateTime)                                                         extends ApplicationCommand
case class UnsubscribeFromApi(actor: Actor, apiIdentifier: ApiIdentifier, timestamp: LocalDateTime)                                                     extends ApplicationCommand
case class UpdateRedirectUris(actor: Actor, oldRedirectUris: List[String], newRedirectUris: List[String], timestamp: LocalDateTime)                     extends ApplicationCommand

trait GatekeeperSpecificApplicationCommand                                                                                        extends ApplicationCommand {
  def gatekeeperUser: String
}
case class ChangeProductionApplicationName(instigator: UserId, timestamp: LocalDateTime, gatekeeperUser: String, newName: String) extends GatekeeperSpecificApplicationCommand
case class DeclineApplicationApprovalRequest(gatekeeperUser: String, reasons: String, timestamp: LocalDateTime)                   extends GatekeeperSpecificApplicationCommand

case class DeleteApplicationByGatekeeper(gatekeeperUser: String, requestedByEmailAddress: String, reasons: String, timestamp: LocalDateTime)
    extends GatekeeperSpecificApplicationCommand

trait ApplicationCommandFormatters {
  implicit val addClientSecretFormatter                        = Json.format[AddClientSecret]
  implicit val removeClientSecretFormatter                     = Json.format[RemoveClientSecret]
  implicit val addCollaboratorFormatter                        = Json.format[AddCollaborator]
  implicit val removeCollaboratorFormatter                     = Json.format[RemoveCollaborator]
  implicit val changeNameFormatter                             = Json.format[ChangeProductionApplicationName]
  implicit val changePrivacyPolicyLocationFormatter            = Json.format[ChangeProductionApplicationPrivacyPolicyLocation]
  implicit val changeTermsAndConditionsLocationFormatter       = Json.format[ChangeProductionApplicationTermsAndConditionsLocation]
  implicit val changeResponsibleIndividualToSelfFormatter      = Json.format[ChangeResponsibleIndividualToSelf]
  implicit val changeResponsibleIndividualToOtherFormatter     = Json.format[ChangeResponsibleIndividualToOther]
  implicit val verifyResponsibleIndividualFormatter            = Json.format[VerifyResponsibleIndividual]
  implicit val declineResponsibleIndividualFormatter           = Json.format[DeclineResponsibleIndividual]
  implicit val declineApplicationApprovalRequestFormatter      = Json.format[DeclineApplicationApprovalRequest]
  implicit val deleteApplicationByCollaboratorFormatter        = Json.format[DeleteApplicationByCollaborator]
  implicit val deleteApplicationByGatekeeperFormatter          = Json.format[DeleteApplicationByGatekeeper]
  implicit val deleteUnusedApplicationFormatter                = Json.format[DeleteUnusedApplication]
  implicit val deleteProductionCredentialsApplicationFormatter = Json.format[DeleteProductionCredentialsApplication]
  implicit val subscribeToApiFormatter                         = Json.format[SubscribeToApi]
  implicit val unsubscribeFromApiFormatter                     = Json.format[UnsubscribeFromApi]
  implicit val UpdateRedirectUrisFormatter                     = Json.format[UpdateRedirectUris]

  implicit val applicationUpdateRequestFormatter = Union.from[ApplicationCommand]("updateType")
    .and[AddClientSecret]("addClientSecret")
    .and[RemoveClientSecret]("removeClientSecret")
    .and[AddCollaborator]("addCollaborator")
    .and[RemoveCollaborator]("removeCollaborator")
    .and[ChangeProductionApplicationName]("changeProductionApplicationName")
    .and[ChangeProductionApplicationPrivacyPolicyLocation]("changeProductionApplicationPrivacyPolicyLocation")
    .and[ChangeProductionApplicationTermsAndConditionsLocation]("changeProductionApplicationTermsAndConditionsLocation")
    .and[ChangeResponsibleIndividualToSelf]("changeResponsibleIndividualToSelf")
    .and[ChangeResponsibleIndividualToOther]("changeResponsibleIndividualToOther")
    .and[VerifyResponsibleIndividual]("verifyResponsibleIndividual")
    .and[DeclineResponsibleIndividual]("declineResponsibleIndividual")
    .and[DeclineApplicationApprovalRequest]("declineApplicationApprovalRequest")
    .and[DeleteApplicationByCollaborator]("deleteApplicationByCollaborator")
    .and[DeleteApplicationByGatekeeper]("deleteApplicationByGatekeeper")
    .and[DeleteUnusedApplication]("deleteUnusedApplication")
    .and[DeleteProductionCredentialsApplication]("deleteProductionCredentialsApplication")
    .and[SubscribeToApi]("subscribeToApi")
    .and[UnsubscribeFromApi]("unsubscribeFromApi")
    .and[UpdateRedirectUris]("updateRedirectUris")
    .format
}
