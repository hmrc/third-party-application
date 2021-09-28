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

trait MapJsonFormatters {
    implicit def mapReads[K, V](implicit asKey: (String) => K, readsV: Reads[V]): Reads[Map[K, V]] = new Reads[Map[K, V]] {
    type Errors = Seq[(JsPath, Seq[JsonValidationError])]
    
    def process(in: Map[String, JsValue]): JsResult[V] = {
      if(in.size != 1) {
        JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.map.with.one.entry"))))
      }
      else {
        val key = in.keySet.head
        val value = in(key)

        readsV.reads(value)
      }
    }

    def locate(e: Errors, key: String) = e.map { case (path, validationError) => (JsPath \ key) ++ path -> validationError }

    def reads(json: JsValue) = json match {
      case JsArray(jsValues) =>
        jsValues.foldLeft[Either[Errors, Map[K,V]]](Right(Map.empty)) {
          case (acc, JsObject(fs)) => (acc, process(fs.toMap)) match {
            case (Right(vs), JsSuccess(v, _)) => Right(vs + (asKey(fs.keySet.head) -> v))
            case (Right(_), JsError(e)) => Left(locate(e, fs.keySet.head))
            case (Left(e), _: JsSuccess[_]) => Left(e)
            case (Left(e1), JsError(e2)) => Left(e1 ++ locate(e2, fs.keySet.head))
          }
          
          case (acc, _) => Left(Seq(JsPath() -> Seq(JsonValidationError("error.expected.jsobject"))))
        }
        .fold(JsError.apply, res => JsSuccess(res))

      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.jsobject"))))
    }
  }

  implicit def mapWrites[K,V](implicit asString: (K) => String, formatV: Writes[V]): Writes[Map[K, V]] =
    new Writes[Map[K,V]] {
      def writes(o: Map[K,V]): JsValue = {
        JsArray(o.map {
            case (k,v) => JsObject(Seq((asString(k), formatV.writes(v))) )
        }.toSeq)
      }
    }



    
  implicit def listMapReads[K, V](implicit asKey: (String) => K, readsV: Reads[V]): Reads[ListMap[K, V]] = new Reads[ListMap[K, V]] {
    type Errors = Seq[(JsPath, Seq[JsonValidationError])]
    
    def process(in: Map[String, JsValue]): JsResult[V] = {
      if(in.size != 1) {
        JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.map.with.one.entry"))))
      }
      else {
        val key = in.keySet.head
        val value = in(key)

        readsV.reads(value)
      }
    }

    def locate(e: Errors, key: String) = e.map { case (path, validationError) => (JsPath \ key) ++ path -> validationError }

    def reads(json: JsValue) = json match {
      case JsArray(jsValues) =>
        jsValues.foldLeft[Either[Errors, ListMap[K,V]]](Right(ListMap.empty)) {
          case (acc, JsObject(fs)) => (acc, process(fs.toMap)) match {
            case (Right(vs), JsSuccess(v, _)) => Right(vs + (asKey(fs.keySet.head) -> v))
            case (Right(_), JsError(e)) => Left(locate(e, fs.keySet.head))
            case (Left(e), _: JsSuccess[_]) => Left(e)
            case (Left(e1), JsError(e2)) => Left(e1 ++ locate(e2, fs.keySet.head))
          }
          
          case (acc, _) => Left(Seq(JsPath() -> Seq(JsonValidationError("error.expected.jsobject"))))
        }
        .fold(JsError.apply, res => JsSuccess(res))

      case _ => JsError(Seq(JsPath() -> Seq(JsonValidationError("error.expected.jsobject"))))
    }
  }

  implicit def listMapWrites[K,V](implicit asString: (K) => String, formatV: Writes[V]): Writes[ListMap[K, V]] =
    new Writes[ListMap[K,V]] {
      def writes(o: ListMap[K,V]): JsValue = {
        JsArray(o.map {
            case (k,v) => JsObject(Seq((asString(k), formatV.writes(v))) )
        }.toSeq)
      }
    }
}

object MapJsonFormatters extends MapJsonFormatters