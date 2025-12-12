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

package uk.gov.hmrc.thirdpartyapplication.services

import java.time.Clock
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditResult

import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, ClockNow}
import uk.gov.hmrc.thirdpartyapplication.connector.EmailConnector
import uk.gov.hmrc.thirdpartyapplication.domain.models.{ApplicationStateChange, _}
import uk.gov.hmrc.thirdpartyapplication.models.db.StoredApplication
import uk.gov.hmrc.thirdpartyapplication.models.{DeleteApplicationRequest, _}
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.services.query.QueryService

@Singleton
class GatekeeperService @Inject() (
    queryService: QueryService,
    applicationRepository: ApplicationRepository,
    stateHistoryRepository: StateHistoryRepository,
    auditService: AuditService,
    emailConnector: EmailConnector,
    applicationService: ApplicationService,
    val clock: Clock
  )(implicit val ec: ExecutionContext
  ) extends ApplicationLogger with ClockNow {

  def fetchAppStateHistories(): Future[Seq[ApplicationStateHistoryResponse]] = {
    for {
      appsWithHistory <- applicationRepository.fetchProdAppStateHistories()
      history          = appsWithHistory.map(a => ApplicationStateHistoryResponse(a.id, a.name, a.version, a.states.map(s => ApplicationStateHistoryResponse.Item(s.state, s.changedAt))))
    } yield history
  }

  def deleteApplication(applicationId: ApplicationId, request: DeleteApplicationRequest)(implicit hc: HeaderCarrier): Future[ApplicationStateChange] = {
    def audit(app: StoredApplication): Future[AuditResult] = {
      auditService.auditGatekeeperAction(request.gatekeeperUserId, app, ApplicationDeleted, Map("requestedByEmailAddress" -> request.requestedByEmailAddress.text))
    }
    for {
      _ <- applicationService.deleteApplication(applicationId, Some(request), audit)
    } yield Deleted

  }

  val unit: Unit = ()

  val recoverAll: Future[_] => Future[_] = {
    _ recover {
      case e: Throwable => logger.error(e.getMessage); unit
    }
  }
}
