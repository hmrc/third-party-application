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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import cats.implicits._
import cats.data.ValidatedNec
import uk.gov.hmrc.thirdpartyapplication.domain.models.UserId
import uk.gov.hmrc.thirdpartyapplication.domain.models.Role
import uk.gov.hmrc.thirdpartyapplication.domain.models.State
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent
import cats.data.NonEmptyList

abstract class CommandHandler {
  implicit def ec: ExecutionContext
}

object CommandHandler {
  type Result = Future[ValidatedNec[String, NonEmptyList[UpdateApplicationEvent]]]

  def cond(cond: => Boolean, left: String): ValidatedNec[String, Unit] = {
    if(cond) ().validNec[String] else left.invalidNec[Unit]
  }

  def isAdminOnApp(userId: UserId, app: ApplicationData) =
    cond(app.collaborators.find(c => c.role == Role.ADMINISTRATOR && c.userId == userId).nonEmpty, "User must be an ADMIN")

  def isNotInProcessOfBeingApproved(app: ApplicationData) =
    cond(app.state.name == State.PRODUCTION || app.state.name == State.PRE_PRODUCTION || app.state.name == State.TESTING, "App is not in TESTING, in PRE_PRODUCTION or in PRODUCTION")

  def isStandardAccess(app: ApplicationData) =
    cond(app.access.accessType == AccessType.STANDARD, "App must have a STANDARD access type")
}