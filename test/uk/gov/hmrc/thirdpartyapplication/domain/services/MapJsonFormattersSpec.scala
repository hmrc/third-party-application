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

import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.ReferenceId
import play.api.libs.json.Json
import scala.collection.immutable.ListMap
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsError

object MapJsonFormattersSpec {
  case class Key(value: String) extends AnyVal
  implicit val asString: (Key) => String = _.value
  implicit val asKey: (String) => Key = Key(_)
  implicit val keyFormat = Json.valueFormat[Key]

  case class Value(part1: String, part2: Int)
  implicit val valueFormat = Json.format[Value]
}

class MapJsonFormattersSpec extends HmrcSpec {
  import MapJsonFormatters._
  import MapJsonFormattersSpec._

  implicit val format = Json.valueFormat[ReferenceId]
  
  val testValue: ListMap[Key, Value] = ListMap(
    Key("one") -> Value("a", 1),
    Key("two") -> Value("b", 2)
  )

  "MapJsonFormatters" should {
    "write correctly for populated map" in {
      val text = Json.stringify(Json.toJson(testValue))
      println(text)
      text shouldBe """[{"one":{"part1":"a","part2":1}},{"two":{"part1":"b","part2":2}}]"""
    }
    
    "write correctly for empty map" in {
      val text = Json.stringify(Json.toJson(ListMap.empty[Key,Value]))
      println(text)
      text shouldBe """[]"""
    }

    "read correctly when one value" in {
      val text = """[{"one":{"part1":"a","part2":1}}]"""

      Json.parse(text).validate[ListMap[Key, Value]] match {
        case JsSuccess(x, _) => x shouldBe ListMap(Key("one") -> Value("a",1))
        case JsError(_) => fail
      }
    }

    "read correctly when two values" in {
      val text = """[{"one":{"part1":"a","part2":1}},{"two":{"part1":"b","part2":2}}]"""

      Json.parse(text).validate[ListMap[Key, Value]] match {
        case JsSuccess(x, _) => x shouldBe ListMap(Key("one") -> Value("a",1), Key("two") -> Value("b", 2))
        case JsError(_) => fail
      }
    }

    "read correctly when empty" in {
      val text = """[]"""

      Json.parse(text).validate[ListMap[Key, Value]] match {
        case JsSuccess(x, _) => x shouldBe ListMap.empty
        case JsError(_) => fail
      }
    }

    "read error when not a JsArray" in {
      val text = """{"one":{"part1":"a","part2":1}}"""

      Json.parse(text).validate[ListMap[Key, Value]] match {
        case JsSuccess(x, _) => fail
        case JsError(_) => succeed
      }
    }

    "read error when not an object in the array" in {
      val text = """[{"one":{"part1":"a","part2":1}}, 666]"""

      Json.parse(text).validate[ListMap[Key, Value]] match {
        case JsSuccess(x, _) => fail
        case JsError(_) => succeed
      }
    }
    
    "read error when the object in the array has multiple keys" in {
      val text = """[{"one":{"part1":"a","part2":1},"two":{"part1":"b","part2":2}}]"""

      Json.parse(text).validate[ListMap[Key, Value]] match {
        case JsSuccess(x, _) => fail
        case JsError(_) => succeed
      }
    }  
        
    "read error when the object in the array cannot be parsed as V" in {
      val text = """[{"one":{"part1":"a"}}]"""

      Json.parse(text).validate[ListMap[Key, Value]] match {
        case JsSuccess(x, _) => fail
        case JsError(_) => succeed
      }
    } 
  }
}
