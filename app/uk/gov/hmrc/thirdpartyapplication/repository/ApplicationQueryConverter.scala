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

package uk.gov.hmrc.thirdpartyapplication.repository

import scala.reflect.ClassTag

import org.bson.conversions.Bson
import org.mongodb.scala.bson._
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model._

import uk.gov.hmrc.mongo.play.json.Codecs

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.State
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models.Param._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models._

object ApplicationQueryConverter {

  private val excludeDeleted = notEqual("state.name", State.DELETED.toString)

  def first[T <: Param[_]](implicit params: List[Param[_]], ct: ClassTag[T]): Option[T] = params.collect {
    case qp: T => qp
  }.headOption

  def onFirst[T <: Param[_]](fn: (T) => List[Bson])(implicit params: List[Param[_]], ct: ClassTag[T]): List[Bson] = params.collect {
    case qp: T => qp
  }.headOption.fold(List.empty[Bson])(fn)

  def asSubscriptionFilters(implicit params: List[Param[_]]): List[Bson] = {
    if (first[NoSubscriptionsQP.type].isDefined)
      List(size("subscribedApis", 0))
    else if (first[HasSubscriptionsQP.type].isDefined)
      List(Document(s"""{$$expr: {$$gte: [{$$size:"$$subscribedApis"}, 1] }}"""))
    else {
      first[ApiContextQP].fold(List.empty[Bson])(context => {
        val contextFilter = equal("subscribedApis.apiIdentifier.context", Codecs.toBson(context.value))

        first[ApiVersionNbrQP].fold(List(contextFilter))(versionNbr => {
          val versionFilter = equal("subscribedApis.apiIdentifier.version", Codecs.toBson(versionNbr.value))

          List(and(contextFilter, versionFilter))
        })
      })
    }
  }

  def asUserFilters(implicit params: List[Param[_]]): List[Bson] =
    onFirst[UserIdQP](userIdQp =>
      List(equal("collaborators.userId", Codecs.toBson(userIdQp.value)))
    )

  def asEnvironmentFilters(implicit params: List[Param[_]]): List[Bson] =
    onFirst[EnvironmentQP](environmentQp =>
      List(equal("environment", Codecs.toBson(environmentQp.value)))
    )

  def asDeleteRestrictionFilters(implicit params: List[Param[_]]): List[Bson] = {
    def eq(text: String) = List(equal("deleteRestriction.deleteRestrictionType", Codecs.toBson(text)))

    onFirst[DeleteRestrictionQP] {
      _ match {
        case Param.DoNotDeleteQP   => eq("DO_NOT_DELETE")
        case Param.NoRestrictionQP => eq("NO_RESTRICTION")
      }
    }
  }

  // IncludeDeleted is a NOP
  def asIncludeOrExcludeDeletedAppsFilters(implicit params: List[Param[_]]): List[Bson] =
    onFirst[ExcludeDeletedQP.type](_ => {
      List(excludeDeleted)
    })

  def asAccessTypeFilters(implicit params: List[Param[_]]): List[Bson] =
    onFirst[AccessTypeParam[_]](_ match {
      case AnyAccessTypeQP               => List.empty
      case MatchAccessTypeQP(accessType) => List(equal("access.accessType", Codecs.toBson(accessType)))
    })

  def asLastUsedFilters(implicit params: List[Param[_]]): List[Bson] = {
    import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.Implicits.jatInstantFormat

    val a = onFirst[LastUsedAfterQP](qp => {
      List(
        or(
          gte("lastAccess", Codecs.toBson(qp.value)),
          and(
            exists("lastAccess", false),
            gte("createdOn", Codecs.toBson(qp.value))
          )
        )
      )
    })

    val b = onFirst[LastUsedBeforeQP](qp => {
      List(
        or(
          lte("lastAccess", Codecs.toBson(qp.value)),
          and(
            exists("lastAccess", false),
            lte("createdOn", Codecs.toBson(qp.value))
          )
        )
      )
    })

    a ++ b
  }

  def applicationStateMatch(states: State*): Bson = in("state.name", states.map(_.toString): _*)

  def asAppStateFilters(implicit params: List[Param[_]]): List[Bson] =
    onFirst[AppStateParam[_]](_ match {
      case NoStateFilteringQP        => List.empty
      case ActiveStateQP             => List(applicationStateMatch(State.PRE_PRODUCTION, State.PRODUCTION))
      case ExcludeDeletedQP          => List(excludeDeleted)
      case BlockedStateQP            => List(and(equal("blocked", BsonBoolean(true)), excludeDeleted))
      case MatchOneStateQP(state)    => List(equal("state.name", state.toString))
      case MatchManyStatesQP(states) => List(in("state.name", states.toList.map(_.toString): _*))
    }) ++
      onFirst[AppStateBeforeDateQP](qp => List(lte("state.updatedOn", qp.value)))

  def asSearchFilter(implicit params: List[Param[_]]): List[Bson] =
    onFirst[SearchTextQP](qp =>
      List(
        or(
          List("id", "name", "tokens.production.clientId").map(field => regex(field, qp.value, "i")): _* // TODO This list of search fields is a bit naff
        )
      )
    )

  def asNameFilter(implicit params: List[Param[_]]): List[Bson] =
    onFirst[NameQP](qp =>
      List(equal("normalisedName", qp.value.toLowerCase))
    )

  def asVerificationCodeFilter(implicit params: List[Param[_]]): List[Bson] =
    onFirst[VerificationCodeQP](qp =>
      List(equal("state.verificationCode", qp.value))
    )

  def asSingleQueryFilters(implicit params: List[Param[_]]): List[Bson] = {
    onFirst[UniqueFilterParam[_]](_ match {
      case ApplicationIdQP(id)  => List(equal("id", Codecs.toBson(id)))
      case ClientIdQP(id)       => List(equal("tokens.production.clientId", Codecs.toBson(id)))
      case ServerTokenQP(value) => List(equal("tokens.production.accessToken", value))
    })
  }

  def convertToFilter(implicit params: List[Param[_]]): List[Bson] = {
    val individualFilters =
      asSingleQueryFilters ++
        asSubscriptionFilters ++
        asUserFilters ++
        asEnvironmentFilters ++
        asDeleteRestrictionFilters ++
        asIncludeOrExcludeDeletedAppsFilters ++
        asAccessTypeFilters ++
        asLastUsedFilters ++
        asAppStateFilters ++
        asNameFilter ++
        asVerificationCodeFilter ++
        asSearchFilter

    if (individualFilters.isEmpty) {
      List.empty
    } else {
      List(Aggregates.filter(and(individualFilters: _*)))
    }
  }

  def convertToSort(sort: Sorting): List[Bson] = sort match {
    case Sorting.NameAscending         => List(Aggregates.sort(Sorts.ascending("normalisedName")))
    case Sorting.NameDescending        => List(Aggregates.sort(Sorts.descending("normalisedName")))
    case Sorting.SubmittedAscending    => List(Aggregates.sort(Sorts.ascending("createdOn")))
    case Sorting.SubmittedDescending   => List(Aggregates.sort(Sorts.descending("createdOn")))
    case Sorting.LastUseDateAscending  => List(Aggregates.sort(Sorts.ascending("lastAccess")))
    case Sorting.LastUseDateDescending => List(Aggregates.sort(Sorts.descending("lastAccess")))
    case Sorting.NoSorting             => List()
  }

  def identifySort(params: List[SortingParam[_]]): Sorting = {
    params match {
      case SortQP(sort) :: Nil => sort
      case _                   => Sorting.SubmittedAscending
    }
  }

  def pageNumber(params: List[Param[_]]): Int = {
    params.collectFirst {
      case PageNbrQP(value) => value
    }.getOrElse(1)
  }

  def pageSize(params: List[Param[_]]): Int = {
    params.collectFirst {
      case PageSizeQP(value) => value
    }.getOrElse(Int.MaxValue)
  }

}
