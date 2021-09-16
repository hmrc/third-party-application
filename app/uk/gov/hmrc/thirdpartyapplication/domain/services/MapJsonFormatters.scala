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

package uk.gov.hmrc.thirdpartyapplication.domain.services

import play.api.libs.json._
import scala.collection.immutable.ListMap
import play.api.libs.json.Json.fromJson

trait MapJsonFormatters {
  implicit def listMapReads[V](implicit formatV: Reads[V]): Reads[ListMap[String, V]] = new Reads[ListMap[String, V]] {
    def reads(json: JsValue) = json match {
      case JsObject(m) =>
        type Errors = Seq[(JsPath, Seq[JsonValidationError])]

        def locate(e: Errors, key: String) = e.map { case (path, validationError) => (JsPath \ key) ++ path -> validationError }

        m.foldLeft(Right(ListMap.empty): Either[Errors, ListMap[String, V]]) {
          case (acc, (key, value)) => (acc, fromJson[V](value)(formatV)) match {
            case (Right(vs), JsSuccess(v, _)) => Right(vs + (key -> v))
            case (Right(_), JsError(e)) => Left(locate(e, key))
            case (Left(e), _: JsSuccess[_]) => Left(e)
            case (Left(e1), JsError(e2)) => Left(e1 ++ locate(e2, key))
          }
        }.fold(JsError.apply, res => JsSuccess(res))

      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.jsobject"))))
    }
  }

  implicit def listMapWrites[V](implicit formatV: Writes[V]): Writes[ListMap[String, V]] =
    new Writes[ListMap[String,V]] {

      def writes(o: ListMap[String,V]): JsValue = {
        JsObject(o.map {
            case (k,v) => (k, formatV.writes(v) )
        }.toSeq)
      }
    }
}

object CommonJsonFormatters extends MapJsonFormatters