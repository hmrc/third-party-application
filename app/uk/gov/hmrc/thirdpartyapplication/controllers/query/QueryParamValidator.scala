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
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.common.domain.models._

sealed trait QueryParamValidator {
  def paramName: String
  def validate(values: Seq[String]): ErrorsOr[Param[_]]
}

object QueryParamValidator {

  private object SingleValueExpected {

    def apply(paramName: String)(values: Seq[String]): ErrorsOr[String] =
      values.toList match {
        case single :: Nil => single.validNel
        case _             => s"Multiple $paramName query parameters are not permitted".invalidNel
      }
  }

  private object NoValueExpected {

    def apply(paramName: String)(values: Seq[String]): ErrorsOr[Unit] =
      values.toList match {
        case Nil => ().validNel
        case _   => s"No query value is allowed for $paramName".invalidNel
      }
  }

  private object IntValueExpected {
    def apply(paramName: String)(value: String): ErrorsOr[Int] = value.toIntOption.toValidNel(s"$paramName must be an integer value")
  }

  private object PositiveIntValueExpected {

    def apply(paramName: String)(value: String): ErrorsOr[Int] = IntValueExpected(paramName)(value) andThen { i =>
      i.some.filter(_ > 0).toValidNel(s"$paramName must be an positive integer value")
    }
  }

  private object ApplicationIdExpected {
    def apply(paramName: String)(value: String): ErrorsOr[ApplicationId] = ApplicationId(value).toValidNel(s"$value is not a valid application id")
  }

  object ApplicationIdValidator extends QueryParamValidator {
    val paramName                                                      = "applicationId"

    def validate(values: Seq[String]): ErrorsOr[Param.ApplicationIdQP] = {
      SingleValueExpected(paramName)(values) andThen ApplicationIdExpected(paramName) map { Param.ApplicationIdQP(_) }
    }
  }

  object ClientIdValidator extends QueryParamValidator {
    val paramName                                                 = "clientId"

    def validate(values: Seq[String]): ErrorsOr[Param.ClientIdQP] = {
      SingleValueExpected(paramName)(values) map { s => Param.ClientIdQP(ClientId(s)) }
    }
  }

  object ContextValidator extends QueryParamValidator {
    val paramName                                                = "context"

    def validate(values: Seq[String]): ErrorsOr[Param.ContextQP] = {
      SingleValueExpected(paramName)(values) map { s => Param.ContextQP(ApiContext(s)) }
    }
  }

  object VersionNbrValidator extends QueryParamValidator {
    val paramName                                                   = "versionNbr"

    def validate(values: Seq[String]): ErrorsOr[Param.VersionNbrQP] = {
      SingleValueExpected(paramName)(values) map { s => Param.VersionNbrQP(ApiVersionNbr(s)) }
    }
  }

  object NoSubscriptionsValidator extends QueryParamValidator {
    val paramName                                                             = "noSubscriptions"

    def validate(values: Seq[String]): ErrorsOr[Param.NoSubscriptionsQP.type] = {
      NoValueExpected(paramName)(values) map { _ => Param.NoSubscriptionsQP }
    }
  }

  object PageSizeValidator extends QueryParamValidator {
    val paramName                                                 = "pageSize"

    def validate(values: Seq[String]): ErrorsOr[Param.PageSizeQP] = {
      SingleValueExpected(paramName)(values) andThen PositiveIntValueExpected(paramName) map { Param.PageSizeQP(_) }
    }
  }

  object PageNbrValidator extends QueryParamValidator {
    val paramName                                                = "pageNbr"

    def validate(values: Seq[String]): ErrorsOr[Param.PageNbrQP] = {
      SingleValueExpected(paramName)(values) andThen PositiveIntValueExpected(paramName) map { Param.PageNbrQP(_) }
    }
  }

  private val paramValidators: List[QueryParamValidator] = List(
    QueryParamValidator.ApplicationIdValidator,
    QueryParamValidator.ClientIdValidator,
    QueryParamValidator.ContextValidator,
    QueryParamValidator.VersionNbrValidator,
    QueryParamValidator.NoSubscriptionsValidator,
    QueryParamValidator.PageSizeValidator,
    QueryParamValidator.PageNbrValidator
  )

  private val validatorLookup: Map[String, QueryParamValidator] = paramValidators.map(pv => pv.paramName.toLowerCase -> pv).toMap

  def parseParams(rawQueryParams: Map[String, Seq[String]]): ErrorsOr[List[Param[_]]] = {
    val paramValidations = rawQueryParams.map {
      case (k, vs) =>
        val validator = validatorLookup.get(k.toLowerCase).toValidNel(s"$k is not a valid query parameter")
        validator.andThen(_.validate(vs))
    }

    val z: ValidatedNel[ErrorMessage, List[Param[_]]] = List.empty.validNel

    paramValidations.foldRight(z) {
      case (Valid(p1), Valid(p2))     => Valid(p1 :: p2)
      case (Invalid(e1), Invalid(e2)) => Invalid(e1 <+> e2)
      case (Invalid(e1), Valid(_))    => Invalid(e1)
      case (Valid(_), Invalid(e2))    => Invalid(e2)
    }
  }

}
