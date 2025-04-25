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

package uk.gov.hmrc.apiplatform.modules.subscriptionfields.domain.models

// NOT TO BE USED FOR JSON FORMATTING !!
object ThreeDMap {

  type Type[X, Y, Z, V] = Map[X, Map[Y, Map[Z, V]]]

  def empty[X, Y, Z, V, W]: Type[X, Y, Z, V] = Map.empty

  def map[X, Y, Z, V, W](fn: (X, Y, Z, V) => W)(in: Type[X, Y, Z, V]): Type[X, Y, Z, W] = {
    in.flatMap {
      case (x, m2) => {
        Map(x -> m2.flatMap {
          case (y, m3) => {
            Map(y -> m3.flatMap {
              case (z, v) => Map(z -> fn(x, y, z, v))
            })
          }
        })
      }
    }
  }

  def filter[X, Y, Z, V](fn: (X, Y, Z, V) => Boolean)(in: Type[X, Y, Z, V]): Type[X, Y, Z, V] = {
    in.flatMap {
      case (a, m2) => {
        val n2 = m2.flatMap {
          case (b, m3) => {
            val n3 = m3.flatMap {
              case (c, v) if (fn(a, b, c, v)) => Map(c -> v)
              case _                          => Map.empty[Z, V]
            }
            if (n3.isEmpty) Map.empty[Y, Map[Z, V]] else Map(b -> n3)
          }
        }
        if (n2.isEmpty) Map.empty[X, Map[Y, Map[Z, V]]] else Map(a -> n2)
      }
    }
  }

  def get[X, Y, Z, V](t: (X, Y, Z))(in: Type[X, Y, Z, V]): Option[V] = {
    in.get(t._1).flatMap(
      _.get(t._2).flatMap(
        _.get(t._3)
      )
    )
  }

  // Brought from APM - seeming not needed (yet)
  //
  // def flatten[X, Y, Z, V](in: Type[X, Y, Z, V]): Type[X, Y, Z, V] = {
  //   in.flatMap {
  //     case (x, ys) if ys.isEmpty => Map.empty[X, Map[Y, Map[Z, V]]]
  //     case (x, ys)               => Map(x -> ys.filterNot(yzs => yzs._2.isEmpty))
  //   }
  //     .filterNot(xys => xys._2.isEmpty)
  // }

}
