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

import uk.gov.hmrc.apiplatform.modules.applications.query.ErrorsOr
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.models._
import uk.gov.hmrc.apiplatform.modules.applications.query.domain.services._

object ParamsValidator {

  def parseAndValidateParams(rawQueryParams: Map[String, Seq[String]], rawHeaders: Map[String, Seq[String]], extraHeaders: Map[String, String]): ErrorsOr[List[Param[_]]] = {
    val queryParams       = QueryParamsValidator.parseParams(rawQueryParams)
    val headerParams      = HeaderValidator.parseHeaders(rawHeaders ++ extraHeaders.map { case (k, v) => k -> Seq(v) })
    val allParamsOrErrors = queryParams combine headerParams

    allParamsOrErrors.andThen(allParams => {
      ParamsCombinationValidator.validateParamCombinations(allParams)
        .map(_ => allParams)
    })
  }
}
