/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.util

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders.{LOGGED_IN_USER_EMAIL_HEADER, LOGGED_IN_USER_NAME_HEADER}

object HeaderCarrierHelper {

  val DEVELOPER_EMAIL_KEY = "developerEmail"
  val DEVELOPER_FULLNAME_KEY = "developerFullName"

  def headersToUserContext(hc: HeaderCarrier) =
    userContextFromHeaders(hc.headers.toMap)

  private def userContextFromHeaders(headers: Map[String, String]) = {
    def mapHeader(mapping: (String, String)): Option[(String, String)] =
      headers.get(mapping._1) map (mapping._2 -> URLDecoder.decode(_, StandardCharsets.UTF_8.toString))

    val email = mapHeader(LOGGED_IN_USER_EMAIL_HEADER -> DEVELOPER_EMAIL_KEY)
    val name = mapHeader(LOGGED_IN_USER_NAME_HEADER -> DEVELOPER_FULLNAME_KEY)

    List(email, name).flatten.toMap
  }
}
