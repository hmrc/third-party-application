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

package uk.gov.hmrc.apiplatformmicroservice.thirdpartyapplication.services

import uk.gov.hmrc.apiplatform.modules.common.domain.models._
import uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models._

trait SubscriptionFieldsData {
  val context1 = ApiContext("C1")
  val context2 = ApiContext("C2")
  val context3 = ApiContext("C3")
  val context4 = ApiContext("C4")

  val version1 = ApiVersionNbr("V1")
  val version2 = ApiVersionNbr("V2")

  val fieldName1 = FieldName("Fa")
  val fieldName2 = FieldName("Fb")
  val fieldName3 = FieldName("Fc")

  def fieldDef(c: Int, v: Int, f: Int) = {
    val cs = "abcdefghijklmnopqrstuxwxyz".charAt(c)
    val vs = "abcdefghijklmnopqrstuxwxyz".charAt(v)
    val fs = "abcdefghijklmnopqrstuxwxyz".charAt(f)
    FieldDefinition(FieldName(s"F$cs$vs$fs"), s"field $f", "", FieldDefinitionType.STRING, s"short $f", None)
  }

  def fv(c: Int, v: Int, f: Int) = FieldValue(s"$c-$v-$f")

  val fieldDefns: ApiFieldMap[FieldDefinition] = Map(
    context1 -> Map(
      version1 -> Map(
        fieldName1 -> fieldDef(1, 1, 1),
        fieldName2 -> fieldDef(1, 1, 2)
      ),
      version2 -> Map(
        fieldName1 -> fieldDef(1, 2, 1),
        fieldName2 -> fieldDef(1, 2, 2)
      )
    ),
    context2 -> Map(
      version1 -> Map(
        fieldName1 -> fieldDef(2, 1, 1)
      ),
      version2 -> Map(
        fieldName1 -> fieldDef(2, 2, 1),
        fieldName2 -> fieldDef(2, 2, 2)
      )
    ),
    context3 -> Map(
      version1 -> Map(
        fieldName1 -> fieldDef(3, 1, 1),
        fieldName2 -> fieldDef(3, 1, 2),
        fieldName3 -> fieldDef(3, 1, 3)
      )
    ),
    context4 -> Map(
      version1 -> Map(
        fieldName1 -> fieldDef(4, 1, 1),
        fieldName2 -> fieldDef(4, 1, 2),
        fieldName3 -> fieldDef(4, 1, 3)
      )
    )
  )

  val fieldValues: ApiFieldMap[FieldValue] = Map(
    context1 -> Map(
      version1 -> Map(
        fieldName1 -> fv(1, 1, 1),
        fieldName2 -> fv(1, 1, 2)
      ),
      version2 -> Map(
        fieldName1 -> fv(1, 2, 1),
        fieldName2 -> fv(1, 2, 2)
      )
    ),
    context2 -> Map(
      version1 -> Map(
        fieldName1 -> fv(2, 1, 1)
      ),
      version2 -> Map(
        fieldName1 -> fv(2, 2, 1),
        fieldName2 -> fv(2, 2, 2)
      )
    ),
    context3 -> Map(
      version1 -> Map(
        fieldName1 -> fv(3, 1, 1),
        fieldName2 -> fv(3, 1, 2)
      )
    )
  )
}
