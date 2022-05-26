package uk.gov.hmrc.utils

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}

abstract class ServerBaseISpec
  extends BaseISpec with GuiceOneServerPerSuite with TestApplication with ScalaFutures with DefaultAwaitTimeout with FutureAwaits {

  override implicit lazy val app: Application = appBuilder.build()

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(4, Seconds), interval = Span(1, Seconds))

}
