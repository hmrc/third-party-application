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

import uk.gov.hmrc.thirdpartyapplication.controllers.query._
import org.bson.conversions.Bson
import org.mongodb.scala.model._
import uk.gov.hmrc.thirdpartyapplication.controllers.query.Param._
import scala.reflect.ClassTag
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model._
import org.mongodb.scala.bson.collection.immutable.Document
import uk.gov.hmrc.mongo.play.json.Codecs
import uk.gov.hmrc.thirdpartyapplication.controllers.query.DeleteRestrictionFilter.DoNotDelete
import uk.gov.hmrc.thirdpartyapplication.controllers.query.DeleteRestrictionFilter.NoRestriction

object ApplicationQueryConverter {

  def first[T <: Param[_]](implicit params: List[Param[_]], ct: ClassTag[T]): Option[T] = params.collect {
    case qp : T => qp
  }.headOption

  def asSubscriptionFilters(implicit params: List[Param[_]]): List[Bson] = {
    if(first[NoSubscriptionsQP.type].isDefined)
      List(Aggregates.filter(size("subscribedApis", 0)))

    else if(first[HasSubscriptionsQP.type].isDefined)
      List(Aggregates.filter(Document(s"""{$$expr: {$$gte: [{$$size:"$$subscribedApis"}, 1] }}""")))

    else {
      first[ApiContextQP].fold( List.empty[Bson] )( context => {
        val contextFilter = Aggregates.filter(equal("subscribedApis.apiIdentifier.context", Codecs.toBson(context.value)))

        first[ApiVersionNbrQP].fold( List(contextFilter) )( versionNbr => {
          val versionFilter = Aggregates.filter(equal("subscribedApis.apiIdentifier.version", Codecs.toBson(versionNbr.value)))

          List(and(contextFilter, versionFilter))
        })
      })
    }
  }   

  def asUserFilters(implicit params: List[Param[_]]): List[Bson] = {
    first[UserIdQP].fold( List.empty[Bson] )( userIdQp =>
      List(Aggregates.filter(equal("collaborators.userId", Codecs.toBson(userIdQp.value))))
    )
  }

  def asEnvironmentFilters(implicit params: List[Param[_]]): List[Bson] = {
    first[EnvironmentQP].fold( List.empty[Bson] )( environmentQp =>
      List(Aggregates.filter(equal("environment", Codecs.toBson(environmentQp.value))))
    )
  }

  def asDeleteRestrictionFilters(implicit params: List[Param[_]]): List[Bson] = {
    first[DeleteRestrictionQP].fold( List.empty[Bson] )( deleteRestrictionQp => {
      val deleteRestrictionValue = deleteRestrictionQp.value match {
        case DoNotDelete => "DO_NOT_DELETE"
        case NoRestriction => "NO_RESTRICTION"
      }

      List(Aggregates.filter(equal("deleteRestriction.deleteRestrictionType", Codecs.toBson(deleteRestrictionValue))))
    })
  }

  def convertToFilter(implicit params: List[Param[_]]): List[Bson] = {
    asSubscriptionFilters ++ asUserFilters ++ asEnvironmentFilters ++ asDeleteRestrictionFilters
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

  def hasAnySubscriptionFilter(params: List[Param[_]]): Boolean =
    params.find(_ match {
      case _ : SubscriptionQP[_] => true
      case _ => false
    }).isDefined

  def hasSpecificSubscriptionFilter(params: List[Param[_]]): Boolean =
    params.find(_ match {
      case ApiContextQP(_) => true
      case _ => false
    }).isDefined
}
