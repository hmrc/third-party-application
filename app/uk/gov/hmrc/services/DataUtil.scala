/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.services

object DataUtil {
  /**
   * Joins two maps based on common keys and throws exception if a key is not found in any map
   * @param map1 Map[K, T]
   * @param map2 Map[K, S]
   * @param mapper function taking (T, S) and returns R
   * @param map1Error error function returning exception if K is not found in map1
   * @param map2Error error function returning exception if K is not found in map2
   * @tparam K Key to join the maps
   * @tparam T value type in map1
   * @tparam S value type in map2
   * @tparam R return value type
   * @return returns a Seq of R
   */
  def zipper[K, T, S, R](map1: Map[K, T], map2: Map[K, S], mapper: (T, S) => R,
                         map1Error: K => Exception, map2Error: K => Exception): Seq[R] = {
    val results = for (key <- map1.keys ++ map2.keys)
      yield mapper(map1.getOrElse(key, throw map1Error(key)), map2.getOrElse(key, throw map2Error(key)))
    results.toSeq
  }
}
