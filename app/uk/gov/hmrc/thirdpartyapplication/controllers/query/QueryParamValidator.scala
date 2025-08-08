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

import java.time.Instant
import scala.util.Try

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNel
import cats.implicits._

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.domain.services.InstantJsonFormatter
import uk.gov.hmrc.apiplatform.modules.applications.access.domain.models.AccessType

sealed trait QueryParamValidator {
  def paramName: String
  def validate(values: Seq[String]): ErrorsOr[Param[_]]
}

object QueryParamValidator {

  private object SingleValueExpected {

    def apply(paramName: String)(values: Seq[String]): ErrorsOr[String] =
      values.toList match {
        case Nil           => s"$paramName requires a single value".invalidNel
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

  private object BooleanValueExpected {
    def apply(paramName: String)(value: String): ErrorsOr[Boolean] = value.toBooleanOption.toValidNel(s"$paramName must be true or false")
  }

  private object InstantValueExpected {

    def apply(paramName: String)(value: String): ErrorsOr[Instant] =
      Try(Instant.from(InstantJsonFormatter.lenientFormatter.parse(value)))
        .toOption
        .fold[ErrorsOr[Instant]](s"$paramName of $value must be a valid date".invalidNel)(d => d.validNel)
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
    val paramName = "applicationId"

    def validate(values: Seq[String]): ErrorsOr[Param.ApplicationIdQP] = {
      SingleValueExpected(paramName)(values) andThen ApplicationIdExpected(paramName) map { Param.ApplicationIdQP(_) }
    }
  }

  object ClientIdValidator extends QueryParamValidator {
    val paramName = "clientId"

    def validate(values: Seq[String]): ErrorsOr[Param.ClientIdQP] = {
      SingleValueExpected(paramName)(values) map { s => Param.ClientIdQP(ClientId(s)) }
    }
  }

  object ApiContextValidator extends QueryParamValidator {
    val paramName = "context"

    def validate(values: Seq[String]): ErrorsOr[Param.ApiContextQP] = {
      SingleValueExpected(paramName)(values) map { s => Param.ApiContextQP(ApiContext(s)) }
    }
  }

  object ApiVersionNbrValidator extends QueryParamValidator {
    val paramName = "versionNbr"

    def validate(values: Seq[String]): ErrorsOr[Param.ApiVersionNbrQP] = {
      SingleValueExpected(paramName)(values) map { s => Param.ApiVersionNbrQP(ApiVersionNbr(s)) }
    }
  }

  object HasSubscriptionsValidator extends QueryParamValidator {
    val paramName = "oneOrMoreSubscriptions" // TODO - hasSubscription ???

    def validate(values: Seq[String]): ErrorsOr[Param.HasSubscriptionsQP.type] = {
      NoValueExpected(paramName)(values) map { _ => Param.HasSubscriptionsQP }
    }
  }

  object NoSubscriptionsValidator extends QueryParamValidator {
    val paramName = "noSubscriptions"

    def validate(values: Seq[String]): ErrorsOr[Param.NoSubscriptionsQP.type] = {
      NoValueExpected(paramName)(values) map { _ => Param.NoSubscriptionsQP }
    }
  }

  object PageSizeValidator extends QueryParamValidator {
    val paramName = "pageSize"

    def validate(values: Seq[String]): ErrorsOr[Param.PageSizeQP] = {
      SingleValueExpected(paramName)(values) andThen PositiveIntValueExpected(paramName) map { Param.PageSizeQP(_) }
    }
  }

  object PageNbrValidator extends QueryParamValidator {
    val paramName = "pageNbr"

    def validate(values: Seq[String]): ErrorsOr[Param.PageNbrQP] = {
      SingleValueExpected(paramName)(values) andThen PositiveIntValueExpected(paramName) map { Param.PageNbrQP(_) }
    }
  }

  private object AppStatusFilterExpected {
    def apply(value: String): ErrorsOr[AppStatusFilter] = AppStatusFilter(value).toValidNel(s"$value is not a valid status filter")
  }

  object StatusValidator extends QueryParamValidator {
    val paramName = "status"

    def validate(values: Seq[String]): ErrorsOr[Param.StatusFilterQP] = {
      SingleValueExpected(paramName)(values) andThen AppStatusFilterExpected.apply map { Param.StatusFilterQP(_) }
    }
  }

  private object SortExpected {
    def apply(value: String): ErrorsOr[Sorting] = Sorting(value).toValidNel(s"$value  is not a valid sort")
  }

  object SortValidator extends QueryParamValidator {
    val paramName = "sort"

    def validate(values: Seq[String]): ErrorsOr[Param.SortQP] = {
      SingleValueExpected(paramName)(values) andThen SortExpected.apply map { sort => Param.SortQP(sort) }
    }
  }
  
  private object UserIdExpected {
    def apply(paramName: String)(value: String): ErrorsOr[UserId] = UserId.apply(value).toValidNel(s"$value is not a valid user id")
  }


  object UserIdValidator extends QueryParamValidator {
    val paramName = "userId"

    def validate(values: Seq[String]): ErrorsOr[Param.UserIdQP] = {
      SingleValueExpected(paramName)(values) andThen UserIdExpected(paramName) map { Param.UserIdQP(_) }
    }
  }
  
  object AccessTypeValidator extends QueryParamValidator {

    def parseText(value: String): ErrorsOr[Option[AccessType]] = {
      value match {
        case "ANY" => None.validNel
        case text  => AccessType(text).fold[ErrorsOr[Option[AccessType]]](
            s"$value is not a valid access type".invalidNel
          )(at =>
            at.some.validNel
          )
      }
    }
    val paramName                                              = "accessType"

    def validate(values: Seq[String]): ErrorsOr[Param.AccessTypeQP] = {
      SingleValueExpected(paramName)(values) andThen parseText map { oType => Param.AccessTypeQP(oType) }
    }
  }

  object SearchTextValidator extends QueryParamValidator {
    val paramName = "search"

    def validate(values: Seq[String]): ErrorsOr[Param.SearchTextQP] = {
      SingleValueExpected(paramName)(values) map { text => Param.SearchTextQP(text) }
    }
  }

  object IncludeDeletedValidator extends QueryParamValidator {
    val paramName = "includeDeleted"

    def validate(values: Seq[String]): ErrorsOr[Param.IncludeDeletedQP] = {
      SingleValueExpected(paramName)(values) andThen BooleanValueExpected(paramName) map { bool => Param.IncludeDeletedQP(bool) }
    }
  }

  private object DeleteRestrictionExpected {
    def apply(value: String): ErrorsOr[DeleteRestrictionFilter] = DeleteRestrictionFilter(value).toValidNel(s"$value is not a valid delete restriction filter")
  }

  object DeleteRestrictionValidator extends QueryParamValidator {
    val paramName = "deleteRestriction"

    def validate(values: Seq[String]): ErrorsOr[Param.DeleteRestrictionQP] = {
      SingleValueExpected(paramName)(values) andThen DeleteRestrictionExpected.apply map { value => Param.DeleteRestrictionQP(value) }
    }
  }

  private object EnvironmentExpected {
    def apply(value: String): ErrorsOr[Environment] = Environment.apply(value).toValidNel(s"$value is not a valid environment")

  }
  object EnvironmentValidator extends QueryParamValidator {
    val paramName = "environment"

    def validate(values: Seq[String]): ErrorsOr[Param.EnvironmentQP] = {
      SingleValueExpected(paramName)(values) andThen EnvironmentExpected.apply map { value => Param.EnvironmentQP(value) }
    }
  }

  object LastUseBeforeValidator extends QueryParamValidator {
    val paramName = "lastUsedBefore"

    def validate(values: Seq[String]): ErrorsOr[Param.LastUsedBeforeQP] = {
      SingleValueExpected(paramName)(values) andThen InstantValueExpected(paramName) map { date => Param.LastUsedBeforeQP(date) }
    }
  }

  object LastUseAfterValidator extends QueryParamValidator {
    val paramName = "lastUsedAfter"

    def validate(values: Seq[String]): ErrorsOr[Param.LastUsedAfterQP] = {
      SingleValueExpected(paramName)(values) andThen InstantValueExpected(paramName) map { date => Param.LastUsedAfterQP(date) }
    }
  }

  private val paramValidators: List[QueryParamValidator] = List(
    QueryParamValidator.AccessTypeValidator,
    QueryParamValidator.ApiContextValidator,
    QueryParamValidator.ApiVersionNbrValidator,
    QueryParamValidator.ApplicationIdValidator,
    QueryParamValidator.ClientIdValidator,
    QueryParamValidator.DeleteRestrictionValidator,
    QueryParamValidator.EnvironmentValidator,
    QueryParamValidator.HasSubscriptionsValidator,
    QueryParamValidator.IncludeDeletedValidator,
    QueryParamValidator.LastUseBeforeValidator,
    QueryParamValidator.LastUseAfterValidator,
    QueryParamValidator.NoSubscriptionsValidator,
    QueryParamValidator.PageSizeValidator,
    QueryParamValidator.PageNbrValidator,
    QueryParamValidator.StatusValidator,
    QueryParamValidator.SortValidator,
    QueryParamValidator.SearchTextValidator,
    QueryParamValidator.UserIdValidator
  )

  private val validatorLookup: Map[String, QueryParamValidator] = paramValidators.map(pv => pv.paramName.toLowerCase -> pv).toMap

  def parseParams(rawQueryParams: Map[String, Seq[String]]): ErrorsOr[List[Param[_]]] = {
    val paramValidations = rawQueryParams.map {
      case (k, vs) =>
        val validator = validatorLookup.get(k.toLowerCase).toValidNel(s"$k is not a valid query parameter")
        validator.andThen(_.validate(vs.filterNot(_.isBlank())))
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
