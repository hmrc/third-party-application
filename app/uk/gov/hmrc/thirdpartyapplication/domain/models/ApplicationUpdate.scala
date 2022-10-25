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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import play.api.libs.json.Json
import uk.gov.hmrc.play.json.Union
import java.time.LocalDateTime

trait ApplicationUpdate {
  def timestamp: LocalDateTime
}
case class AddClientSecret(instigator: UserId, email: String, secretValue: String, clientSecret: ClientSecret, timestamp: LocalDateTime) extends ApplicationUpdate
case class RemoveClientSecret(instigator: UserId, email: String, clientSecretId: String, timestamp: LocalDateTime) extends ApplicationUpdate
case class AddCollaborator(instigator: UserId, email: String,  collaborator: Collaborator, adminsToEmail:Set[String], timestamp: LocalDateTime) extends ApplicationUpdate
case class RemoveCollaborator(instigator: UserId, email: String,  collaborator: Collaborator, adminsToEmail:Set[String], notifyCollaborator: Boolean, timestamp: LocalDateTime) extends ApplicationUpdate
case class ChangeProductionApplicationPrivacyPolicyLocation(instigator: UserId, timestamp: LocalDateTime, newLocation: PrivacyPolicyLocation) extends ApplicationUpdate
case class ChangeProductionApplicationTermsAndConditionsLocation(instigator: UserId, timestamp: LocalDateTime, newLocation: TermsAndConditionsLocation) extends ApplicationUpdate
case class ChangeResponsibleIndividualToSelf(instigator: UserId, timestamp: LocalDateTime, name: String, email: String) extends ApplicationUpdate
case class ChangeResponsibleIndividualToOther(code: String, timestamp: LocalDateTime) extends ApplicationUpdate
case class VerifyResponsibleIndividual(instigator: UserId, timestamp: LocalDateTime, requesterName: String, riName: String, riEmail: String) extends ApplicationUpdate
case class DeclineResponsibleIndividual(code: String, timestamp: LocalDateTime) extends ApplicationUpdate
case class DeclineResponsibleIndividualDidNotVerify(code: String, timestamp: LocalDateTime) extends ApplicationUpdate

trait GatekeeperApplicationUpdate extends ApplicationUpdate {
  def gatekeeperUser: String
}
case class AddCollaboratorGatekeeper(gatekeeperUser: String, email: String,  collaborator: Collaborator, adminsToEmail:Set[String], timestamp: LocalDateTime) extends GatekeeperApplicationUpdate
case class RemoveCollaboratorGateKeeper(gatekeeperUser: String,  email: String,  collaborator: Collaborator, adminsToEmail:Set[String], notifyCollaborator: Boolean, timestamp: LocalDateTime) extends GatekeeperApplicationUpdate
case class ChangeProductionApplicationName(instigator: UserId, timestamp: LocalDateTime, gatekeeperUser: String, newName: String) extends GatekeeperApplicationUpdate
case class DeclineApplicationApprovalRequest(gatekeeperUser: String, reasons: String, timestamp: LocalDateTime) extends GatekeeperApplicationUpdate

trait ApiPlatformJobsApplicationUpdate extends ApplicationUpdate {
  def jobId: String
}

case class RemoveCollaboratorPlatformJobs(jobId: String, email: String,  collaborator: Collaborator, adminsToEmail:Set[String], notifyCollaborator: Boolean, timestamp: LocalDateTime) extends ApiPlatformJobsApplicationUpdate

trait ApplicationUpdateFormatters {
  implicit val addClientSecretFormatter = Json.format[AddClientSecret]
  implicit val removeClientSecretFormatter = Json.format[RemoveClientSecret]
  implicit val addCollaboratorFormatter = Json.format[AddCollaborator]
  implicit val addCollaboratorGatekeeperFormatter = Json.format[AddCollaboratorGatekeeper]
  implicit val removeCollaboratorFormatter = Json.format[RemoveCollaborator]
  implicit val removeCollaboratorGatekeeperFormatter = Json.format[RemoveCollaboratorGateKeeper]
  implicit val removeCollaboratorPlatformJobsFormatter = Json.format[RemoveCollaboratorPlatformJobs]
  implicit val changeNameFormatter = Json.format[ChangeProductionApplicationName]
  implicit val changePrivacyPolicyLocationFormatter = Json.format[ChangeProductionApplicationPrivacyPolicyLocation]
  implicit val changeTermsAndConditionsLocationFormatter = Json.format[ChangeProductionApplicationTermsAndConditionsLocation]
  implicit val changeResponsibleIndividualToSelfFormatter = Json.format[ChangeResponsibleIndividualToSelf]
  implicit val changeResponsibleIndividualToOtherFormatter = Json.format[ChangeResponsibleIndividualToOther]
  implicit val verifyResponsibleIndividualFormatter = Json.format[VerifyResponsibleIndividual]
  implicit val declineResponsibleIndividualFormatter = Json.format[DeclineResponsibleIndividual]
  implicit val declineApplicationApprovalRequestFormatter = Json.format[DeclineApplicationApprovalRequest]

  implicit val applicationUpdateRequestFormatter = Union.from[ApplicationUpdate]("updateType")
    .and[AddClientSecret]("addClientSecret")
    .and[RemoveClientSecret]("removeClientSecret")
    .and[AddCollaborator]("addCollaborator")
    .and[AddCollaboratorGatekeeper]("addCollaboratorGatekeeper")
    .and[RemoveCollaborator]("removeCollaborator")
    .and[RemoveCollaboratorGateKeeper]("removeCollaboratorGateKeeper")
    .and[RemoveCollaboratorPlatformJobs]("removeCollaboratorPlatformJobs")
    .and[ChangeProductionApplicationName]("changeProductionApplicationName")
    .and[ChangeProductionApplicationPrivacyPolicyLocation]("changeProductionApplicationPrivacyPolicyLocation")
    .and[ChangeProductionApplicationTermsAndConditionsLocation]("changeProductionApplicationTermsAndConditionsLocation")
    .and[ChangeResponsibleIndividualToSelf]("changeResponsibleIndividualToSelf")
    .and[ChangeResponsibleIndividualToOther]("changeResponsibleIndividualToOther")
    .and[VerifyResponsibleIndividual]("verifyResponsibleIndividual")
    .and[DeclineResponsibleIndividual]("declineResponsibleIndividual")
    .and[DeclineApplicationApprovalRequest]("declineApplicationApprovalRequest")
    .format
}
