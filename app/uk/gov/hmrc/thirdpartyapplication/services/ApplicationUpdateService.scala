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

package uk.gov.hmrc.thirdpartyapplication.services

import akka.actor.ActorSystem
import cats.data.EitherT
import cats.implicits._
import org.apache.commons.net.util.SubnetUtils
import org.joda.time.Duration.standardMinutes
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatform.modules.common.services.{ApplicationLogger, EitherTHelper}
import uk.gov.hmrc.apiplatform.modules.submissions.services.SubmissionsService
import uk.gov.hmrc.apiplatform.modules.uplift.services.UpliftNamingService
import uk.gov.hmrc.http.{ForbiddenException, HeaderCarrier, NotFoundException}
import uk.gov.hmrc.lock.{LockKeeper, LockMongoRepository, LockRepository}
import uk.gov.hmrc.play.audit.http.connector.AuditResult
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.controllers.{AddCollaboratorRequest, AddCollaboratorResponse, DeleteApplicationRequest, FixCollaboratorRequest}
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ActorType._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role._
import uk.gov.hmrc.thirdpartyapplication.domain.models.State._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.repository.{ApplicationRepository, StateHistoryRepository, SubscriptionRepository}
import uk.gov.hmrc.thirdpartyapplication.services.AuditAction._
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator
import uk.gov.hmrc.thirdpartyapplication.util.http.HeaderCarrierUtils._
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders._

import java.time.{Clock, LocalDateTime}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.Future.{apply => _, _}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Try}

@Singleton
class ApplicationUpdateService @Inject()(applicationRepository: ApplicationRepository,
                                   upliftNamingService: UpliftNamingService)
                                   (implicit val ec: ExecutionContext) extends ApplicationLogger {

  def updateApplicationName(applicationId: ApplicationId, newName: String): EitherT[Future, ApplicationNameValidationResult, ApplicationData] = {
    logger.info(s"Trying to update the Application Name to $newName for application ${applicationId.value}")

    val ET = EitherTHelper.make[ApplicationNameValidationResult]
    for {
      _          <- ET.fromEitherF(validateApplicationName(newName))
      updatedApp <- ET.liftF(applicationRepository.updateApplicationName(applicationId, newName))
    } yield updatedApp
  }

  private def validateApplicationName(name: String): Future[Either[ApplicationNameValidationResult, Unit]] = {
    upliftNamingService.validateApplicationName(name, None).map(_ match {
      case ValidName => Right()
      case errResult => Left(errResult)
    })
  }

}
