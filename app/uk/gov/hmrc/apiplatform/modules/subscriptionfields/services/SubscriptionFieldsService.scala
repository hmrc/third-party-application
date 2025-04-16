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

package uk.gov.hmrc.apiplatform.modules.subscriptionfields.services

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.http.HeaderCarrier

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.connector.ApiSubscriptionFieldsConnector.FieldErrors
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models._

@Singleton
class SubscriptionFieldsService @Inject() (
    subscriptionFieldsConnector: ApiSubscriptionFieldsConnector
  )(implicit ec: ExecutionContext
  ) {

  def fetchFieldValuesWithDefaults(deployedTo: Environment, clientId: ClientId, subscriptions: Set[ApiIdentifier])(implicit hc: HeaderCarrier): Future[ApiFieldMap[FieldValue]] = {

    def filterBySubs[V](data: ApiFieldMap[V]): ApiFieldMap[V] = {
      ThreeDMap.filter((c: ApiContext, v: ApiVersionNbr, _: FieldName, _: V) => subscriptions.contains(ApiIdentifier(c, v)))(data)
    }

    def fillFields(defns: ApiFieldMap[FieldDefinition])(fields: ApiFieldMap[FieldValue]): ApiFieldMap[FieldValue] = {
      ThreeDMap.map((c: ApiContext, v: ApiVersionNbr, fn: FieldName, fv: FieldDefinition) => ThreeDMap.get((c, v, fn))(fields).getOrElse(FieldValue("")))(defns)
    }

    for {
      definitions <- subscriptionFieldsConnector.fetchAllFieldDefinitions()
      subsDefs     = filterBySubs(definitions)
      fields      <- subscriptionFieldsConnector.fetchFieldValues(clientId)
      subsFields   = filterBySubs(fields)
      filledFields = fillFields(subsDefs)(subsFields)
    } yield filledFields
  }

  def createFieldValues(
      clientId: ClientId,
      deployedTo: Environment,
      apiIdentifier: ApiIdentifier
    )(implicit hc: HeaderCarrier
    ): Future[Either[FieldErrors, Unit]] = {
    for {
      fieldValues      <- fetchFieldValuesWithDefaults(deployedTo, clientId, Set(apiIdentifier))
      fieldValuesForApi = ApiFieldMap.extractApi(apiIdentifier)(fieldValues)
      fvResults        <- subscriptionFieldsConnector.saveFieldValues(clientId, apiIdentifier, fieldValuesForApi)
    } yield fvResults
  }

}
