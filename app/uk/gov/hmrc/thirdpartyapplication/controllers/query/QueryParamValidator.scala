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
import uk.gov.hmrc.thirdpartyapplication.models.{AccessTypeFilter, ApplicationSort, DeleteRestrictionFilter, StatusFilter}

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

  object StatusFilterValidator extends QueryParamValidator {

    def parseText(value: String): ErrorsOr[StatusFilter] = {
      import uk.gov.hmrc.thirdpartyapplication.models.StatusFilter._
      value match {
        case "CREATED"                                     => Created.validNel
        case "PENDING_RESPONSIBLE_INDIVIDUAL_VERIFICATION" => PendingResponsibleIndividualVerification.validNel
        case "PENDING_GATEKEEPER_CHECK"                    => PendingGatekeeperCheck.validNel
        case "PENDING_SUBMITTER_VERIFICATION"              => PendingSubmitterVerification.validNel
        case "ACTIVE"                                      => Active.validNel
        case "DELETED"                                     => WasDeleted.validNel
        case "EXCLUDING_DELETED"                           => ExcludingDeleted.validNel
        case "BLOCKED"                                     => Blocked.validNel
        case "ANY"                                         => NoFiltering.validNel
        case _                                             => s"$value is not a valid status filter".invalidNel
      }
    }

    val paramName = "status"

    def validate(values: Seq[String]): ErrorsOr[Param.StatusFilterQP] = {
      SingleValueExpected(paramName)(values) andThen parseText map { Param.StatusFilterQP(_) }
    }
  }

  object SortValidator extends QueryParamValidator {

    def parseText(value: String): ErrorsOr[ApplicationSort] = {
      import ApplicationSort._
      value match {
        case "NAME_ASC"       => NameAscending.validNel
        case "NAME_DESC"      => NameDescending.validNel
        case "SUBMITTED_ASC"  => SubmittedAscending.validNel
        case "SUBMITTED_DESC" => SubmittedDescending.validNel
        case "LAST_USE_ASC"   => LastUseDateAscending.validNel
        case "LAST_USE_DESC"  => LastUseDateDescending.validNel
        case "NO_SORT"        => NoSorting.validNel
        case _                => s"$value is not a valid sort".invalidNel
      }
    }
    val paramName                                           = "sort"

    def validate(values: Seq[String]): ErrorsOr[Param.SortQP] = {
      SingleValueExpected(paramName)(values) andThen parseText map { sort => Param.SortQP(sort) }
    }
  }

  object AccessTypeValidator extends QueryParamValidator {

    def parseText(value: String): ErrorsOr[AccessTypeFilter] = {
      import AccessTypeFilter._

      value match {
        case "STANDARD"   => StandardAccess.validNel
        case "ROPC"       => ROPCAccess.validNel
        case "PRIVILEGED" => PrivilegedAccess.validNel
        case "ANY"        => NoFiltering.validNel
        case _            => s"$value is not a valid sort".invalidNel
      }
    }
    val paramName                                            = "accessType"

    def validate(values: Seq[String]): ErrorsOr[Param.AccessTypeQP] = {
      SingleValueExpected(paramName)(values) andThen parseText map { sort => Param.AccessTypeQP(sort) }
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

  object DeleteRestrictionValidator extends QueryParamValidator {

    def parseText(value: String): ErrorsOr[DeleteRestrictionFilter] = {
      import DeleteRestrictionFilter._

      value match {
        case "DO_NOT_DELETE"  => DoNotDelete.validNel
        case "NO_RESTRICTION" => NoRestriction.validNel
        case _                => s"$value is not a valid delete restriction".invalidNel
      }
    }
    val paramName                                                   = "deleteRestriction"

    def validate(values: Seq[String]): ErrorsOr[Param.DeleteRestrictionQP] = {
      SingleValueExpected(paramName)(values) andThen parseText map { sort => Param.DeleteRestrictionQP(sort) }
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
    QueryParamValidator.ApplicationIdValidator,
    QueryParamValidator.ClientIdValidator,
    QueryParamValidator.ApiContextValidator,
    QueryParamValidator.ApiVersionNbrValidator,
    QueryParamValidator.HasSubscriptionsValidator,
    QueryParamValidator.NoSubscriptionsValidator,
    QueryParamValidator.PageSizeValidator,
    QueryParamValidator.PageNbrValidator,
    QueryParamValidator.StatusFilterValidator,
    QueryParamValidator.SortValidator,
    QueryParamValidator.AccessTypeValidator,
    QueryParamValidator.SearchTextValidator,
    QueryParamValidator.IncludeDeletedValidator,
    QueryParamValidator.DeleteRestrictionValidator,
    QueryParamValidator.LastUseBeforeValidator,
    QueryParamValidator.LastUseAfterValidator
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
