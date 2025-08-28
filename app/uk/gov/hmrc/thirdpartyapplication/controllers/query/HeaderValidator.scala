/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers.query

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.syntax.all._

import uk.gov.hmrc.thirdpartyapplication.util.http.HttpHeaders

sealed trait HeaderValidator {
  def headerName: String
  def validate(values: Seq[String]): ErrorsOr[Param[_]]
}

private object HeaderValidator {
  type ErrorMessage = String
  type ErrorsOr[A]  = ValidatedNel[ErrorMessage, A]

  private object SingleValueExpected {

    def apply(headerName: String)(values: Seq[String]): ErrorsOr[String] =
      values.toList match {
        case single :: Nil => single.validNel
        case _             => s"Multiple $headerName values are not permitted".invalidNel
      }
  }

  object ServerTokenHeaderValidator extends HeaderValidator {
    val headerName = HttpHeaders.SERVER_TOKEN_HEADER

    def validate(values: Seq[String]): ErrorsOr[Param.ServerTokenQP] = {
      SingleValueExpected(headerName)(values) map { Param.ServerTokenQP(_) }
    }
  }

  object InternalUserAgentValidator extends HeaderValidator {
    val headerName = HttpHeaders.INTERNAL_USER_AGENT

    def validate(values: Seq[String]): ErrorsOr[Param.UserAgentQP] = {
      SingleValueExpected(headerName)(values) map { Param.UserAgentQP(_) }
    }
  }

  private val headerValidators: List[HeaderValidator] = List(
    HeaderValidator.ServerTokenHeaderValidator,
    HeaderValidator.InternalUserAgentValidator
  )

  def parseHeaders(rawHeaders: Map[String, Seq[String]]): ErrorsOr[List[Param[_]]] = {
    val z: ValidatedNel[ErrorMessage, List[Param[_]]] = List.empty.validNel

    headerValidators.map(hv => {
      rawHeaders.find {
        case (k, vs) => hv.headerName.equalsIgnoreCase(k)
      }
        .map { mh =>
          hv.validate(mh._2)
        }
    })
      .collect {
        case Some(h) => h
      }
      .foldRight(z) {
        case (Valid(p1), Valid(p2))     => Valid(p1 :: p2)
        case (Invalid(e1), Invalid(e2)) => Invalid(e1 <+> e2)
        case (Invalid(e1), Valid(_))    => Invalid(e1)
        case (Valid(_), Invalid(e2))    => Invalid(e2)
      }
  }
}
