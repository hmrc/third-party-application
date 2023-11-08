package uk.gov.hmrc.thirdpartyapplication.domain.models

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.{ApplicationState, State}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

object ApplicationStateExamples extends FixedClock {
  val testing: ApplicationState = ApplicationState(State.TESTING, None, updatedOn = now)

  def pendingGatekeeperApproval(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PENDING_GATEKEEPER_APPROVAL, Some(requestedByEmail), Some(requestedByName), updatedOn = now)

  def pendingRequesterVerification(requestedByEmail: String, requestedByName: String, verificationCode: String) =
    ApplicationState(State.PENDING_REQUESTER_VERIFICATION, Some(requestedByEmail), Some(requestedByName), Some(verificationCode), updatedOn = now)

  def pendingResponsibleIndividualVerification(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION, Some(requestedByEmail), Some(requestedByName), updatedOn = now)

  def preProduction(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PRE_PRODUCTION, Some(requestedByEmail), Some(requestedByName), updatedOn = now)

  def production(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.PRODUCTION, Some(requestedByEmail), Some(requestedByName), updatedOn = now)

  def deleted(requestedByEmail: String, requestedByName: String) =
    ApplicationState(State.DELETED, Some(requestedByEmail), Some(requestedByName), updatedOn = now)
}
