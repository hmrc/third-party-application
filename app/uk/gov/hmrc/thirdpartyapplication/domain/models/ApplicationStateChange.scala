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


sealed trait ApplicationStateChange

case object UpliftRequested extends ApplicationStateChange

case object UpliftApproved extends ApplicationStateChange

case object UpliftRejected extends ApplicationStateChange

case object UpliftVerified extends ApplicationStateChange

case object VerificationResent extends ApplicationStateChange

case object Deleted extends ApplicationStateChange

trait Blocked extends ApplicationStateChange

case object Blocked extends Blocked

trait Unblocked extends ApplicationStateChange

case object Unblocked extends Unblocked
