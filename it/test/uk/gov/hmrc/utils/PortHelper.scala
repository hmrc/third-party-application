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

package uk.gov.hmrc.utils

import java.net.ServerSocket
import scala.annotation.tailrec
import scala.language.postfixOps

import play.api.Logger

object PortHelper {
  val rnd            = new scala.util.Random
  val range          = 8000 to 39999
  val usedPorts      = List[Int]()
  val logger: Logger = Logger(this.getClass())

  // scalastyle:off magic.number

  @tailrec
  def randomAvailable: Int = {
    range(rnd.nextInt(range length)) match {
      case 8080   => randomAvailable
      case 8090   => randomAvailable
      case p: Int => {
        available(p) match {
          case false => {
            logger.debug(s"Port $p is in use, trying another")
            randomAvailable
          }
          case true  => {
            logger.debug("Taking port : " + p)
            usedPorts :+ p
            p
          }
        }
      }
    }
  }

  private def available(p: Int): Boolean = {
    var socket: ServerSocket = null
    try {
      if (!usedPorts.contains(p)) {
        socket = new ServerSocket(p)
        socket.setReuseAddress(true)
        true
      } else {
        false
      }
    } catch {
      case t: Throwable => false
    } finally {
      if (socket != null) socket.close()
    }
  }
  // scalastyle:on magic.number
}
