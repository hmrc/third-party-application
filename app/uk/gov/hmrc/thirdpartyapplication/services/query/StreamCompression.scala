/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.services.query

import java.time.Instant
import scala.collection.mutable.{ArrayBuffer, ListBuffer, Map}

import org.apache.pekko.stream.scaladsl.Source

import play.api.libs.json._
import uk.gov.hmrc.play.json.Union

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApiIdentifier, ApplicationId}
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

sealed trait Output

case class OutputApp[A](
    app: A,
    subscriptions: Option[Set[Int]] = None
  )(implicit val fmt: Format[A]
  ) extends Output

case class OutputSubscription(apiIdentifier: ApiIdentifier) extends Output

object OutputApp {
  implicit def fmt[A](implicit fmt: OFormat[A]): OFormat[OutputApp[A]] = Json.format[OutputApp[A]]
}

object OutputSubscription {
  implicit val fmt: OFormat[OutputSubscription] = Json.format[OutputSubscription]
}

object Output {

  def fmt[A](implicit fmt: OFormat[A]): OFormat[Output] = Union.from[Output]("otype")
    .and[OutputApp[A]]("app")
    .and[OutputSubscription]("sub")
    .format
}

case class SimpleApp(
    id: ApplicationId,
    name: ApplicationName,
    createdOn: Instant,
    lastAccess: Instant
  )

object SimpleApp {
  implicit val fmt: OFormat[SimpleApp] = Json.format[SimpleApp]
}

object StreamCompression {
  type LookupTable = Map[ApiIdentifier, Int]

  def compress(in: (LookupTable, ApplicationRepository.LimitedApp)): (LookupTable, List[Output]) = {
    val (lt, app) = in
    app.subscriptions match {
      case None => (lt, List(OutputApp(SimpleApp(app.id, app.name, app.createdOn, app.lastAccess), None)))

      case Some(allSubs) =>
        val (knownSubs, newSubs) = allSubs.partition(id => lt.contains(id))

        val output = ListBuffer.empty[Output]

        newSubs.foreach { ns =>
          val nextKey = lt.size + 1
          lt.put(ns, nextKey)
          output += OutputSubscription(ns)
        }
        val replacedSubs: Set[Int] = allSubs.map(id => lt(id))

        output += OutputApp(SimpleApp(app.id, app.name, app.createdOn, app.lastAccess), Some(replacedSubs))
        (lt, output.toList)
    }
  }

  def decompress(in: (ArrayBuffer[ApiIdentifier], List[Output])): (ArrayBuffer[ApiIdentifier], List[ApplicationRepository.LimitedApp]) = {
    val lookupTable = in._1
    val resultList  = ListBuffer.empty[ApplicationRepository.LimitedApp]

    in._2.foreach(_ match {
      case OutputSubscription(id)                                         => lookupTable.append(id)
      case OutputApp(SimpleApp(id, name, createdOn, lastAccess), subKeys) =>
        val subs: Option[Set[ApiIdentifier]] = subKeys.map(_.map(k => lookupTable(k - 1)))
        resultList.append(ApplicationRepository.LimitedApp(id, name, createdOn, lastAccess, subs))
    })

    (lookupTable, resultList.toList)
  }

  def compressStream(in: Source[ApplicationRepository.LimitedApp, _]): Source[List[Output], _] = {
    in.statefulMap[LookupTable, List[Output]](() => Map.empty[ApiIdentifier, Int])(
      (map, app) => compress((map, app)),
      _ => None
    )
  }
}
