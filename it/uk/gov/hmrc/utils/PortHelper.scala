package uk.gov.hmrc.utils

import play.api.Logger

import java.net.ServerSocket
import scala.annotation.tailrec
import scala.language.postfixOps

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
