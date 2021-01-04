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

package uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class DataUtilSpec extends AsyncHmrcSpec {

  def zip(first: Int, second: String) = s"$first-$second"

  def error(from: String)(key: String) = new RuntimeException(s"$from: test error")

  val map1 = Map("A" -> 1, "B" -> 2)
  val map2 = Map("A" -> "9", "B" -> "8")
  "DataUtil" should {

    "zip apps with history" in {
      val result = DataUtil.zipper(map1, map2, zip, error("Map1"), error("Map2"))
      result should contain theSameElementsAs Seq("1-9", "2-8")
    }

    "throw map1 error for inconsistency" in {
      val ex = intercept[RuntimeException](DataUtil.zipper(map1, map2 + ("C" -> "7"), zip, error("Map1"), error("Map2")))
      ex.getMessage shouldBe "Map1: test error"
    }
    "throw map2 error for inconsistency" in {
      val ex = intercept[RuntimeException](DataUtil.zipper(map1 + ("C" -> 3), map2, zip, error("Map1"), error("Map2")))
      ex.getMessage shouldBe "Map2: test error"
    }
  }
}
