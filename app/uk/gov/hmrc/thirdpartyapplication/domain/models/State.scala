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

import uk.gov.hmrc.thirdpartyapplication.domain.utils.EnumJson

object State extends Enumeration {
  type State = Value

  /* The order of the following declarations is important since it defines the ordering of the enumeration.
   * Be very careful when changing this, code may be relying on certain values being larger/smaller than others. */
  val TESTING, PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION, PENDING_GATEKEEPER_APPROVAL, PENDING_REQUESTER_VERIFICATION, PRE_PRODUCTION, PRODUCTION, DELETED = Value

  implicit val format = EnumJson.enumFormat(State)
}
